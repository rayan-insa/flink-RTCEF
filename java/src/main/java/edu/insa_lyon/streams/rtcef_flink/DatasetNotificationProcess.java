package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.windowing.ProcessAllWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;

import stream.GenericEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ProcessAllWindowFunction that emits Dataset notifications to Kafka.
 * 
 * At the end of each time window (bucket), this function:
 * 1. Computes the bucket ID from the window end time.
 * 2. Maintains a rolling list of the last K bucket IDs in state.
 * 3. Deletes old buckets that fall outside the K window.
 * 4. Constructs a JSON message with the dataset metadata.
 * 5. Emits the JSON string downstream (to Kafka).
 * 
 * This implements the "Last K" strategy from the original RTCEF Collector.
 */
public class DatasetNotificationProcess 
        extends ProcessAllWindowFunction<GenericEvent, String, TimeWindow> {

    /** Maximum number of buckets to include in each dataset notification. */
    private final int lastK;
    
    /** Prefix for bucket paths on disk. */
    private final String pathPrefix;
    
    /** State: Rolling list of bucket IDs (includes all, not just last K). */
    private transient ListState<Long> allBucketsState;
    
    /** Dataset version counter. */
    private transient long datasetVersion;
    
    /** JSON mapper for constructing output. */
    private transient ObjectMapper mapper;

    /**
     * Constructor.
     * 
     * @param lastK Number of buckets to include in each dataset (e.g., 3).
     * @param pathPrefix Prefix for bucket paths (e.g., "/opt/flink/data/saved_datasets/dataset_").
     */
    public DatasetNotificationProcess(int lastK, String pathPrefix) {
        this.lastK = lastK;
        this.pathPrefix = pathPrefix;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        
        // Initialize state for all buckets
        ListStateDescriptor<Long> descriptor = new ListStateDescriptor<>(
            "allBuckets",
            Types.LONG
        );
        allBucketsState = getRuntimeContext().getListState(descriptor);
        
        datasetVersion = 0;
        mapper = new ObjectMapper();
    }

    /**
     * Delete a bucket directory and all its contents.
     * 
     * @param bucketId The bucket ID (timestamp) to delete
     */
    private void deleteBucket(long bucketId) {
        Path bucketPath = Paths.get(pathPrefix + bucketId);
        
        if (!Files.exists(bucketPath)) {
            System.out.println("[DatasetNotificationProcess] Bucket already deleted or doesn't exist: " + bucketPath);
            return;
        }
        
        try {
            // Delete all files in the directory, then the directory itself
            Files.walk(bucketPath)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        System.err.println("[DatasetNotificationProcess] Failed to delete: " + path + " - " + e.getMessage());
                    }
                });
            
            System.out.println("[DatasetNotificationProcess] Deleted old bucket: " + bucketPath);
        } catch (IOException e) {
            System.err.println("[DatasetNotificationProcess] Error walking bucket directory: " + e.getMessage());
        }
    }

    @Override
    public void process(Context context, Iterable<GenericEvent> elements, Collector<String> out) 
            throws Exception {
        
        // Get the window START time as the bucket ID (epoch seconds)
        // This matches the bucketStart calculation in InferenceJob's FileSink
        TimeWindow window = context.window();
        long bucketId = window.getStart() / 1000;
        
        // Count events in this window (for logging)
        long eventCount = 0;
        for (GenericEvent e : elements) {
            eventCount++;
        }
        
        System.out.println("[DatasetNotificationProcess] Window closed: bucketId=" + bucketId 
            + ", events=" + eventCount);
        
        // Add new bucket to history
        List<Long> allBuckets = new ArrayList<>();
        for (Long id : allBucketsState.get()) {
            allBuckets.add(id);
        }
        allBuckets.add(bucketId);
        
        // Identify and delete old buckets (those outside the K window)
        while (allBuckets.size() > lastK) {
            long oldBucketId = allBuckets.remove(0);
            deleteBucket(oldBucketId);
        }
        
        // Update state
        allBucketsState.clear();
        allBucketsState.addAll(allBuckets);
        
        // Construct JSON notification (only includes last K buckets)
        ObjectNode json = mapper.createObjectNode();
        json.put("dataset_id", "ds-" + datasetVersion);
        json.put("path_prefix", pathPrefix);
        
        ArrayNode rangeArray = mapper.createArrayNode();
        for (Long id : allBuckets) {
            rangeArray.add(id);
        }
        json.set("buckets_range", rangeArray);
        
        json.put("version", datasetVersion);
        json.put("timestamp", bucketId);
        json.put("bucket_count", allBuckets.size());
        
        datasetVersion++;
        
        // Emit JSON string
        String jsonStr = mapper.writeValueAsString(json);
        System.out.println("[DatasetNotificationProcess] Emitting dataset notification: " + jsonStr);
        out.collect(jsonStr);
    }
}
