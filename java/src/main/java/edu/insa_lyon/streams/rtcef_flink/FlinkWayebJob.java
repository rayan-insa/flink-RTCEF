package edu.insa_lyon.streams.rtcef_flink;

import java.time.Duration;

import org.apache.flink.core.fs.Path;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.connector.file.sink.FileSink;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.DefaultRollingPolicy;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import stream.GenericEvent;

import edu.insa_lyon.streams.rtcef_flink.utils.DynamicBucketAssigner;
import edu.insa_lyon.streams.rtcef_flink.utils.GenericEventEncoder;
import edu.insa_lyon.streams.rtcef_flink.utils.PredictionOutput;
import edu.insa_lyon.streams.rtcef_flink.utils.ReportOutput;


public class FlinkWayebJob {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // --- 1. Parse Parameters ---
        // ParameterTool handles --key value parsing automatically
        ParameterTool params = ParameterTool.fromArgs(args);

        // Make parameters available in the web interface configuration
        env.getConfig().setGlobalJobParameters(params);

        // Extract values with Defaults (fallback if not provided)
        String loadPath = params.get("modelPath", "/opt/flink/data/saved_models/tmp.spst");
        String inputPath = params.get("inputPath", "/opt/flink/data/maritime.csv");
        int horizon = params.getInt("horizon", 600);
        double runConfidenceThreshold = params.getDouble("threshold", 0.3);
        int maxSpread = params.getInt("maxSpread", 5);

        // Collector Params
        String outputPath = "data/saved_datasets";
        final long bucketSizeSec = params.getLong("bucketSize", 86400); // 1 day default
        final String namingPrefix = params.get("naming", "dataset_");

        System.out.println("--- Job Configuration ---");
        System.out.println("Model Path: " + loadPath);
        System.out.println("Input Path: " + inputPath);
        System.out.println("Horizon: " + horizon);
        System.out.println("Threshold: " + runConfidenceThreshold);
        System.out.println("Max Spread: " + maxSpread);
        System.out.println("-------------------------");

        // --- 2. Setup Data Source ---
        FileSource<String> fileSource = FileSource
            .forRecordStreamFormat(new TextLineInputFormat(), new Path(inputPath))
            .build();

        DataStream<GenericEvent> mainStream = env
                .fromSource(fileSource, WatermarkStrategy.noWatermarks(), "Maritime Source")
                // Use the Parser Class directly (produces GenericEvent)
                .flatMap(new MaritimeParser()) 
                .assignTimestampsAndWatermarks(
                    WatermarkStrategy.<GenericEvent>forBoundedOutOfOrderness(Duration.ofSeconds(60))
                    .withTimestampAssigner((event, timestamp) -> event.timestamp() * 1000L)
                );

        // Collector datastream
        DataStream<Tuple2<String, GenericEvent>> bucketedStream = mainStream
                .map(event -> {
                    long bucketStart = event.timestamp() - (event.timestamp() % bucketSizeSec);
                    String bucketName = namingPrefix + bucketStart; 
                    return Tuple2.of(bucketName, event);
                })
                // Use GenericEvent.class for type info
                .returns(Types.TUPLE(Types.STRING, Types.GENERIC(GenericEvent.class)));

        FileSink<Tuple2<String, GenericEvent>> sink = FileSink
                .<Tuple2<String, GenericEvent>>forRowFormat(new Path(outputPath), new GenericEventEncoder())
                .withBucketAssigner(new DynamicBucketAssigner())
                .withRollingPolicy(
                        DefaultRollingPolicy.builder()
                                .withInactivityInterval(Duration.ofMinutes(60))
                                .withMaxPartSize(1024 * 1024 * 128)
                                .build())
                .build();

        bucketedStream.sinkTo(sink).name("Dataset Collector");

        // --- 3. Process each event ---
        SingleOutputStreamOperator<ReportOutput> reportStream = mainStream
            .keyBy(e -> e.getValueOf("mmsi").toString())
            .process(new FlinkEngine(
                loadPath,
                horizon,
                runConfidenceThreshold,
                maxSpread
            ));

        DataStream<String> detectionStream = reportStream
            .getSideOutput(FlinkEngine.MATCH_TAG);

        DataStream<PredictionOutput> predictionStream = reportStream
            .getSideOutput(FlinkEngine.PRED_TAG);

        reportStream.print("REPORT");         // Prints the structured POJO
        detectionStream.print("ALERT");     // Prints "Detected Pattern at..."
        predictionStream.print("FORECAST"); // Prints PRED{ts=..., prob=0.85, window=[+10, +15]}

        env.execute("Wayeb Flink Job");
    }
}