package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;

import java.time.Duration;

/**
 * CollectorJob - Collector Component for the RTCEF Framework
 * 
 * This job implements the Collector component as described in Paper Section
 * 4.2.
 * It organizes streaming maritime data into time buckets using a sliding window
 * approach.
 * 
 * Architecture:
 * 1. INPUT: Reads from data/maritime.csv (simulating the Input Stream)
 * 2. PARSE: Uses MaritimeEventParser to convert CSV lines to MaritimeEvent
 * POJOs
 * 3. WATERMARK: Assigns event-time timestamps and watermarks for windowing
 * 4. WINDOW: Uses SlidingEventTimeWindows with 4-week window size and 1-week
 * slide
 * 5. OUTPUT: DatasetWriter writes window contents to JSON files in
 * data/saved_datasets/
 * and emits file path messages (simulating "Datasets" topic)
 * 
 * Window Configuration:
 * - Window Size: 4 weeks (representing the full training dataset size)
 * - Slide Step: 1 week (representing the bucket size for updates)
 * 
 * Usage:
 * flink run collector-job.jar --inputPath data/maritime.csv
 * 
 * @author RTCEF Team
 */
public class CollectorJob {

    // Window configuration constants (Paper Section 4.2)
    private static final long WINDOW_SIZE_WEEKS = 4;
    private static final long SLIDE_SIZE_WEEKS = 1;

    // Convert weeks to milliseconds for Flink Time windows
    private static final long MS_PER_WEEK = 7L * 24L * 60L * 60L * 1000L;

    // For testing with smaller data, use scaled-down windows (in minutes)
    private static final boolean USE_SCALED_WINDOWS = true;
    private static final long SCALED_WINDOW_SIZE_MINUTES = 4; // 4 minutes instead of 4 weeks
    private static final long SCALED_SLIDE_SIZE_MINUTES = 1; // 1 minute instead of 1 week

    public static void main(String[] args) throws Exception {

        // 1. Create Flink execution environment
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // 2. Parse command-line parameters
        ParameterTool params = ParameterTool.fromArgs(args);
        env.getConfig().setGlobalJobParameters(params);

        // Input path (default: data/maritime.csv)
        String inputPath = params.get("inputPath", "data/maritime.csv");

        // Whether to use scaled (test) windows or production windows
        boolean useScaledWindows = params.getBoolean("scaled", USE_SCALED_WINDOWS);

        System.out.println("=== Collector Job Configuration ===");
        System.out.println("Input Path: " + inputPath);
        System.out.println("Using Scaled Windows: " + useScaledWindows);
        if (useScaledWindows) {
            System.out.println("Window Size: " + SCALED_WINDOW_SIZE_MINUTES + " minutes");
            System.out.println("Slide Size: " + SCALED_SLIDE_SIZE_MINUTES + " minute(s)");
        } else {
            System.out.println("Window Size: " + WINDOW_SIZE_WEEKS + " weeks");
            System.out.println("Slide Size: " + SLIDE_SIZE_WEEKS + " week(s)");
        }
        System.out.println("====================================");

        // 3. Define the file source
        FileSource<String> fileSource = FileSource
                .forRecordStreamFormat(new TextLineInputFormat(), new Path(inputPath))
                .build();

        // 4. Create watermark strategy with event-time timestamp extraction
        // Maritime data has timestamp in column 0 (Unix epoch in seconds)
        WatermarkStrategy<MaritimeEvent> watermarkStrategy = WatermarkStrategy
                .<MaritimeEvent>forBoundedOutOfOrderness(Duration.ofSeconds(60))
                .withTimestampAssigner(new SerializableTimestampAssigner<MaritimeEvent>() {
                    @Override
                    public long extractTimestamp(MaritimeEvent event, long recordTimestamp) {
                        // Maritime timestamps are in seconds, Flink expects milliseconds
                        return event.getTimestamp() * 1000L;
                    }
                });

        // 5. Build the pipeline - use MaritimeParser.parseLine() for POJO conversion
        DataStream<MaritimeEvent> eventStream = env
                .fromSource(fileSource, WatermarkStrategy.noWatermarks(), "Maritime CSV Source")
                .flatMap(new org.apache.flink.api.common.functions.RichFlatMapFunction<String, MaritimeEvent>() {
                    @Override
                    public void flatMap(String line, org.apache.flink.util.Collector<MaritimeEvent> out) {
                        MaritimeEvent event = MaritimeParser.parseLine(line);
                        if (event != null) {
                            out.collect(event);
                        }
                    }
                })
                .assignTimestampsAndWatermarks(watermarkStrategy);

        // 6. Apply sliding window and process with DatasetWriter
        Time windowSize;
        Time slideSize;

        if (useScaledWindows) {
            windowSize = Time.minutes(SCALED_WINDOW_SIZE_MINUTES);
            slideSize = Time.minutes(SCALED_SLIDE_SIZE_MINUTES);
        } else {
            windowSize = Time.milliseconds(WINDOW_SIZE_WEEKS * MS_PER_WEEK);
            slideSize = Time.milliseconds(SLIDE_SIZE_WEEKS * MS_PER_WEEK);
        }

        DataStream<String> datasetPaths = eventStream
                // Key by MMSI (vessel identifier) for per-vessel datasets
                .keyBy(MaritimeEvent::getMmsi)
                // Apply sliding event-time window
                .window(SlidingEventTimeWindows.of(windowSize, slideSize))
                // Process window contents with DatasetWriter
                .process(new DatasetWriter());

        // 7. Sink: Print the dataset paths (simulates sending to "Datasets" topic)
        datasetPaths
                .print("Dataset Path");

        // 8. Execute the job
        env.execute("RTCEF Collector Job");
    }
}
