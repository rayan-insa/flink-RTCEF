package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ProcessWindowFunction that writes window contents to JSON files.
 * Implements the Dataset Writer for the RTCEF Collector component (Paper
 * Section 4.2).
 * 
 * When a window triggers, this function:
 * 1. Writes all events in the window to a JSON file in data/saved_datasets/
 * 2. Emits a message with the file path to simulate sending to "Datasets" topic
 */
public class DatasetWriter extends ProcessWindowFunction<MaritimeEvent, String, String, TimeWindow> {

    private static final String OUTPUT_DIR = "data/saved_datasets";
    private static final AtomicInteger datasetCounter = new AtomicInteger(1);
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");

    @Override
    public void process(String key, Context context, Iterable<MaritimeEvent> elements, Collector<String> out)
            throws Exception {

        // Ensure output directory exists
        File outputDir = new File(OUTPUT_DIR);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // Generate unique filename with timestamp and version
        int version = datasetCounter.getAndIncrement();
        String timestamp = dateFormat.format(new Date());
        String filename = String.format("dataset_v%d_%s_%s.json", version, key, timestamp);
        String filePath = OUTPUT_DIR + "/" + filename;

        // Get window metadata
        long windowStart = context.window().getStart();
        long windowEnd = context.window().getEnd();

        // Write events to JSON file
        int eventCount = writeEventsToJson(filePath, elements, windowStart, windowEnd, key);

        // Emit the file path message (simulates sending to "Datasets" topic)
        String outputMessage = String.format(
                "{\"path\":\"%s\",\"key\":\"%s\",\"windowStart\":%d,\"windowEnd\":%d,\"eventCount\":%d}",
                filePath, key, windowStart, windowEnd, eventCount);

        out.collect(outputMessage);

        System.out.println(">>> Dataset written: " + filePath + " (" + eventCount + " events)");
    }

    /**
     * Writes all events to a JSON file with metadata.
     * 
     * @param filePath    Path to the output file
     * @param events      Iterable of MaritimeEvent objects
     * @param windowStart Window start timestamp
     * @param windowEnd   Window end timestamp
     * @param key         The grouping key (e.g., MMSI)
     * @return Number of events written
     */
    private int writeEventsToJson(String filePath, Iterable<MaritimeEvent> events,
            long windowStart, long windowEnd, String key) throws IOException {

        int count = 0;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            // Write JSON array start with metadata
            writer.write("{\n");
            writer.write(String.format("  \"metadata\": {\n"));
            writer.write(String.format("    \"key\": \"%s\",\n", key));
            writer.write(String.format("    \"windowStart\": %d,\n", windowStart));
            writer.write(String.format("    \"windowEnd\": %d,\n", windowEnd));
            writer.write(String.format("    \"windowSizeWeeks\": 4,\n"));
            writer.write(String.format("    \"slideStepWeeks\": 1,\n"));
            writer.write(String.format("    \"generatedAt\": \"%s\"\n", new Date().toString()));
            writer.write("  },\n");
            writer.write("  \"events\": [\n");

            boolean first = true;
            for (MaritimeEvent event : events) {
                if (!first) {
                    writer.write(",\n");
                }
                writer.write("    " + event.toJson());
                first = false;
                count++;
            }

            writer.write("\n  ]\n");
            writer.write("}\n");
        }

        return count;
    }
}
