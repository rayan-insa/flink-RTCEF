package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.streaming.api.functions.co.CoProcessFunction;
import org.apache.flink.util.OutputTag;
import scala.collection.JavaConverters;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import stream.GenericEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Comparator;

/**
 * Stateful collector that buckets maritime events into time-indexed CSV files.
 * 
 * This component implements a wall-clock bucketing strategy and coordinates with
 * the Model Factory via an asynchronous feedback loop (ACK) for safe data retention
 * and cleanup.
 * 
 * Key responsibilities:
 * 1. Writing incoming events to local CSV buckets.
 * 2. Emitting dataset notifications when buckets are closed.
 * 3. Safely deleting old buckets only after they are confirmed as processed by the Factory.
 */
public class Collector extends CoProcessFunction<GenericEvent, String, String> {

    private static final Logger LOG = LoggerFactory.getLogger(Collector.class);

    // Params
    private final String outputPath;
    private final String namingPrefix;
    private final long bucketSizeSec;
    private final int lastK;
    private final String pathPrefix;

    // State (Transient / Local per Task)
    private transient long currentBucketId;
    private transient List<Long> bucketHistory; // Keep track of active/past buckets
    private transient ObjectMapper mapper;
    private transient int subtaskIndex;
    private transient long datasetVersion;
    
    // Safety Threshold (ACK)
    private transient long safeDeletionThreshold = -1; 

    public Collector(String outputPath, String namingPrefix, long bucketSizeSec, int lastK) {
        this.outputPath = outputPath;
        this.namingPrefix = namingPrefix;
        this.bucketSizeSec = bucketSizeSec;
        this.lastK = lastK;
        // e.g. /opt/flink/data/saved_datasets/dataset_
        this.pathPrefix = outputPath + (outputPath.endsWith("/") ? "" : "/") + namingPrefix;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        this.mapper = new ObjectMapper();
        this.bucketHistory = new ArrayList<>();
        this.currentBucketId = -1;
        this.datasetVersion = 0;
        
        // Parallelism Support: Get subtask index to ensure unique filenames if parallel > 1
        this.subtaskIndex = getRuntimeContext().getIndexOfThisSubtask();
        
        // Ensure output directory exists provided it's local FS
        Files.createDirectories(Paths.get(outputPath));

        // CRITICAL: Scan for existing buckets to avoid "orphans" after job restart/crash
        rebuildHistoryFromDisk();
    }

    private void rebuildHistoryFromDisk() throws IOException {
        Files.list(Paths.get(outputPath))
            .filter(p -> p.getFileName().toString().startsWith(namingPrefix))
            .forEach(p -> {
                try {
                    String name = p.getFileName().toString();
                    // Remove prefix and suffix if any (e.g. bucket_12345_part-0 -> 12345)
                    String idStr = name.replace(namingPrefix, "");
                    if (idStr.contains("_part-")) {
                        idStr = idStr.substring(0, idStr.indexOf("_part-"));
                    }
                    long bid = Long.parseLong(idStr);
                    if (!bucketHistory.contains(bid)) {
                        bucketHistory.add(bid);
                    }
                } catch (Exception e) {
                    // Ignore non-bucket files
                }
            });
        Collections.sort(bucketHistory);
        if (!bucketHistory.isEmpty()) {
            LOG.info("Rebuilt bucket history from disk: {} buckets found.", bucketHistory.size());
        }
    }

    /**
     * Processes live maritime events and writes them to the current time bucket.
     * 
     * Triggers bucket transitions and notifications when event time passes the
     * current bucket boundary.
     */
    @Override
    public void processElement1(GenericEvent event, Context ctx, org.apache.flink.util.Collector<String> out) throws Exception {
        long eventTimeSec = event.timestamp(); // As input is seconds
        long bucketId = eventTimeSec - (eventTimeSec % bucketSizeSec);

        // 1. Detect Bucket Change (New Bucket)
        if (bucketId > currentBucketId) {
            handleBucketTransition(bucketId, out);
        }

        // 2. Write Record (Synchronous Append)
        writeRecordToBucket(bucketId, event);
    }
    
    /**
     * Processes assembly acknowledgements (ACKs) from the Model Factory.
     * 
     * Updates the safe deletion threshold, allowing old buckets to be permanently
     * removed from the filesystem.
     */
    @Override
    public void processElement2(String jsonAck, Context ctx, org.apache.flink.util.Collector<String> out) throws Exception {
        try {
            JsonNode root = mapper.readTree(jsonAck);
            JsonNode range = root.get("buckets_range");
            
            if (range != null && range.size() > 0) {
                // The factory confirms it has processed this range [Start, ..., End].
                // This implies it is SAFE to delete any bucket OLDER than Start.
                // Because if Factory needed older buckets, they would have been in the range 
                // (or a previous range).
                // Actually, python logic: "bucket_threshold = range[0]".
                // "Delete if bucket < bucket_threshold".
                
                long rangeStart = range.get(0).asLong();
                if (rangeStart > safeDeletionThreshold) {
                    safeDeletionThreshold = rangeStart;
                    // LOG.info("ACK received for range starting {}. Safe to delete buckets < {}", rangeStart, safeDeletionThreshold);
                    performCleanup();
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse ACK: {}", e.getMessage());
        }
    }

    /**
     * Handles switching from the previous bucket to the new one.
     * Including notification and cleanup.
     */
    private void handleBucketTransition(long newBucketId, org.apache.flink.util.Collector<String> out) throws IOException {
        
        if (currentBucketId != -1) {
            // LOG.info("Closing bucket {} -> Switching to {}", currentBucketId, newBucketId);
            
            // Add closed bucket to history
            bucketHistory.add(currentBucketId);
            Collections.sort(bucketHistory);

            // 1. NOTIFY (Emit JSON)
            // We construct the notification for the buckets we have currently in history
            emitDatasetNotification(out);

            // 2. CLEANUP 
            // We check cleanup here too, in case threshold updated but we waited for bucket close
            performCleanup();
        }

        this.currentBucketId = newBucketId;
    }

    /**
     * core I/O: Open, Append, Close.
     */
    private void writeRecordToBucket(long bucketId, GenericEvent event) throws IOException {
    String filename = namingPrefix + bucketId;
    if (getRuntimeContext().getNumberOfParallelSubtasks() > 1) {
            filename += "_part-" + subtaskIndex;
    }
    
    Path path = Paths.get(outputPath, filename);

    
    // 1. Convert Scala Map (from Wayeb) to Java Map
    // This requires that you have added getAttributes() to GenericEvent.scala as discussed previously
    java.util.Map<String, Object> attributes = JavaConverters.mapAsJavaMap(event.getAttributes());

    // 2. Create a JSON Object
    ObjectNode json = mapper.createObjectNode();

    // 3. Add all dynamic attributes (lat, lon, critical_bitstring, etc.)
    // This loop ensures we never lose data, even if fields change in the future.
    for (java.util.Map.Entry<String, Object> entry : attributes.entrySet()) {
        String key = entry.getKey();
        Object val = entry.getValue();

        // Safe typing for Jackson
        if (val instanceof Double) json.put(key, (Double) val);
        else if (val instanceof Integer) json.put(key, (Integer) val);
        else if (val instanceof Long) json.put(key, (Long) val);
        else if (val instanceof Boolean) json.put(key, (Boolean) val);
        else json.put(key, val.toString());
    }

    // 4. Ensure Mandatory Metadata exists
    // (If MaritimeParser didn't put them in the map, we add them now)
    if (!json.has("timestamp")) json.put("timestamp", event.timestamp());
    if (!json.has("id")) json.put("id", String.valueOf(event.id())); 
    // Note: Your JSON parser likely mapped "mmsi" -> "id", so "id" might be redundant if "mmsi" is there.

    // 5. Serialize to String (JSON Line)
    String line = mapper.writeValueAsString(json) + System.lineSeparator();

    // --- FIX ENDS HERE ---

    // BLOCKING I/O
    try {
        Files.write(path, line.getBytes(StandardCharsets.UTF_8), 
            StandardOpenOption.CREATE, 
            StandardOpenOption.APPEND, 
            StandardOpenOption.SYNC); 
    } catch (IOException e) {
        LOG.error("Failed to write record to {}: {}", path, e.getMessage());
        throw e; 
    }
}

    private void emitDatasetNotification(org.apache.flink.util.Collector<String> out) throws IOException {
        // Prepare Bucket Range (Last K or less)
        int size = bucketHistory.size();
        int startIdx = Math.max(0, size - lastK);
        List<Long> range = bucketHistory.subList(startIdx, size);

        ObjectNode json = mapper.createObjectNode();
        json.put("dataset_id", "ds-" + datasetVersion);
        json.put("path_prefix", pathPrefix);
        
        ArrayNode rangeArray = mapper.createArrayNode();
        for (Long id : range) {
            rangeArray.add(id);
        }
        json.set("buckets_range", rangeArray);
        
        json.put("version", datasetVersion);
        json.put("timestamp", currentBucketId); // Timestamp of the trigger
        json.put("bucket_count", range.size());
        
        datasetVersion++;
        
        String jsonStr = mapper.writeValueAsString(json);
        // LOG.info("Collector: Emitting dataset notification v{}: {}", datasetVersion-1, jsonStr);
        out.collect(jsonStr);
    }

    private void performCleanup() {
        // Robust Feedback Logic:
        // Only delete buckets that are strictly OLDER than safeDeletionThreshold.
        // Python logic: if bucket_id < bucket_threshold: delete.
        
        if (safeDeletionThreshold < 0) return; // No Ack received yet
        
        List<Long> toRemove = new ArrayList<>();
        
        for (Long bucketId : bucketHistory) {
            if (bucketId < safeDeletionThreshold) {
                // Delete
                String filename = namingPrefix + bucketId;
                if (getRuntimeContext().getNumberOfParallelSubtasks() > 1) {
                    filename += "_part-" + subtaskIndex;
                }
                Path path = Paths.get(outputPath, filename);
                
                try {
                    if (Files.exists(path)) {
                        Files.delete(path);
                        // LOG.info("ACK-CONFIRMED DELETION: Deleted old bucket {}", path);
                    }
                } catch (IOException e) {
                    LOG.warn("Failed to delete confirmed old bucket {}: {}", path, e.getMessage());
                }
                
                toRemove.add(bucketId);
            }
        }
        
        bucketHistory.removeAll(toRemove);
    }
}