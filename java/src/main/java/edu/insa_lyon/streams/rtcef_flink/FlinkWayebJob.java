package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import edu.insa_lyon.streams.rtcef_flink.utils.PredictionOutput;
import edu.insa_lyon.streams.rtcef_flink.utils.StatOutput;

import org.apache.flink.core.fs.Path;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.api.java.utils.ParameterTool;
import stream.GenericEvent;

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

        DataStream<GenericEvent> stream = env
            .fromSource(fileSource, WatermarkStrategy.noWatermarks(), "CSV Source")
            .flatMap(new MaritimeParser());

        // --- 3. Process each event ---
        SingleOutputStreamOperator<StatOutput> statsStream = stream
            .keyBy(e -> e.getValueOf("mmsi").toString())
            .process(new FlinkEngine(
                loadPath,
                horizon,
                runConfidenceThreshold,
                maxSpread
            ));

        DataStream<String> detectionStream = statsStream
            .getSideOutput(FlinkEngine.MATCH_TAG);

        DataStream<PredictionOutput> predictionStream = statsStream
            .getSideOutput(FlinkEngine.PRED_TAG);

        statsStream.print("STATS");         // Prints the structured POJO
        detectionStream.print("ALERT");     // Prints "Detected Pattern at..."
        predictionStream.print("FORECAST"); // Prints PRED{ts=..., prob=0.85, window=[+10, +15]}

        env.execute("Wayeb Flink Job");
    }
}