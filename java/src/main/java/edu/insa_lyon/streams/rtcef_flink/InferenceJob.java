package edu.insa_lyon.streams.rtcef_flink;

import java.time.Duration;


import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import stream.GenericEvent;

import edu.insa_lyon.streams.rtcef_flink.utils.PredictionOutput;
import edu.insa_lyon.streams.rtcef_flink.utils.ReportOutput;


/**
 * Main Flink Job for RTCEF Inference.
 * 
 * This job performs three parallel tasks:
 * 1. **Collector**: Writes events to disk in time-bucketed folders.
 * 2. **Notifier**: Emits dataset notifications to Kafka when buckets close.
 * 3. **Engine**: Runs Wayeb detection and forecasting with Dynamic Model Loading.
 */
public class InferenceJob {

    private static final Logger LOG = LoggerFactory.getLogger(InferenceJob.class);
    
    // Descriptor for Broadcast State (Model updates)
    public static final MapStateDescriptor<String, String> MODEL_UPDATE_DESCRIPTOR = 
        new MapStateDescriptor<>("modelUpdates", Types.STRING, Types.STRING);

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // Enable Checkpointing (Critical for FileSink to roll on time)
        env.enableCheckpointing(30000); // 30 seconds
        
        // 1.5. Restart Strategy (Resilience)
        // If the coordinator is lost, try 3 times before failing the job.
        env.setRestartStrategy(RestartStrategies.failureRateRestart(3, org.apache.flink.api.common.time.Time.minutes(5), org.apache.flink.api.common.time.Time.seconds(10)));

        // =========================================================================
        // 1. Parse Parameters
        // =========================================================================
        ParameterTool params = ParameterTool.fromArgs(args);
        env.getConfig().setGlobalJobParameters(params);

        // Wayeb Engine Params
        String loadPath = params.get("modelPath", "/opt/flink/data/saved_models/tmp.spst");
        String inputSource = params.get("inputSource", "kafka"); // "file" or "kafka"
        String inputPath = params.get("inputPath", "/opt/flink/data/maritime.csv");
        String inputTopic = params.get("inputTopic", "maritime_input");
        
        int horizon = params.getInt("horizon", 600);
        double runConfidenceThreshold = params.getDouble("threshold", 0.3);
        int maxSpread = params.getInt("maxSpread", 5);

        // Collector Params
        String outputPath = params.get("outputPath", "/opt/flink/data/buckets");
        final long bucketSizeSec = params.getLong("bucketSize", 86400); 
        final String namingPrefix = params.get("naming", "bucket_");

        // Kafka / Notification Params
        final int lastK = params.getInt("lastK", 7);
        final String kafkaServers = params.get("kafka-servers", "kafka:29092");
        final String datasetsTopic = params.get("datasets-topic", "dataset_versions");
        
        // Observer Params
        final String modelReportsTopic = params.get("model-reports-topic", "model_reports");
        final long reportingDistance = params.getLong("reportingDistance", 600); // 10 min in event time seconds
        final double obsTrainDiff = params.getDouble("observer-train-diff", 0.05);
        final double obsOptDiff = params.getDouble("observer-optimize-diff", 0.10);
        final double obsLowScore = params.getDouble("observer-low-score", 0.1);
        final int obsGrace = params.getInt("observer-grace", 3);
        final String instructionsTopic = params.get("instructions-topic", "observer_instructions");

        // Construct full path prefix for notifications
        final String pathPrefix = outputPath + "/" + namingPrefix;

        LOG.info("=== InferenceJob Configuration ===");
        LOG.info("Input Source: {}", inputSource);
        LOG.info("--- Observer ---");
        LOG.info("Reporting Distance (ms): {}", reportingDistance);
        LOG.info("==================================");

        // =========================================================================
        // 2. Setup Data Sources
        // =========================================================================
        
        // A. Maritime Events (Kafka Only)
        // User requested to remove File Source support.
        DataStream<GenericEvent> mainStream;
        
        KafkaSource<String> maritimeSource = KafkaSource.<String>builder()
            .setBootstrapServers(kafkaServers)
            .setTopics(inputTopic)
            .setGroupId("inference-group-maritime")
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setProperty("session.timeout.ms", "45000") // Tolerance for I/O lags
            .setProperty("request.timeout.ms", "60000")
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();
            
        mainStream = env
            .fromSource(maritimeSource, WatermarkStrategy.noWatermarks(), "Maritime Kafka Source")
            .flatMap(new MaritimeParser());

        mainStream = mainStream.assignTimestampsAndWatermarks(
            WatermarkStrategy.<GenericEvent>forBoundedOutOfOrderness(Duration.ofSeconds(60))
            .withTimestampAssigner((event, timestamp) -> event.timestamp() * 1000L)
        );

        // B. Model Updates + Sync Commands (Kafka or Dummy)
        DataStream<String> modelUpdateStream;
        final String engineSyncTopic = params.get("enginesync-topic", "enginesync");
        
        // Always listen to Kafka for Model Updates and Sync commands (Closed Loop)
        // Consume from both factory_reports (model updates) and enginesync (pause/play)
        KafkaSource<String> modelSource = KafkaSource.<String>builder()
            .setBootstrapServers(kafkaServers)
            .setTopics(modelReportsTopic, engineSyncTopic) // Multi-topic subscription
            .setGroupId("inference-group-models")
            .setStartingOffsets(OffsetsInitializer.latest())
            .setProperty("session.timeout.ms", "45000")
            .setProperty("request.timeout.ms", "60000")
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();
        modelUpdateStream = env.fromSource(modelSource, WatermarkStrategy
            .<String>noWatermarks()
            .withIdleness(Duration.ofSeconds(30)), 
            "Model+Sync Source");
        
        // --- Broadcast for Dynamic Models ---
        BroadcastStream<String> broadcastStream = modelUpdateStream.broadcast(MODEL_UPDATE_DESCRIPTOR);

        // =========================================================================
        // 3. Unified Collector with Feedback Loop (ACK)
        // =========================================================================
        
        // A. Feedback Source (Assembled Datasets)
        final String assemblyTopic = params.get("assembly-topic", "assembly_reports");
        
        KafkaSource<String> feedbackSource = KafkaSource.<String>builder()
            .setBootstrapServers(kafkaServers)
            .setTopics(assemblyTopic)
            .setGroupId("collector-feedback-group")
            .setStartingOffsets(OffsetsInitializer.earliest()) // Ensure we catch ACKs even after restart
            .setProperty("session.timeout.ms", "45000")
            .setProperty("request.timeout.ms", "60000")
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();
            
        DataStream<String> feedbackStream = env.fromSource(feedbackSource, WatermarkStrategy.noWatermarks(), "Feedback Source (ACK)");

        DataStream<String> notificationStream;
        
        if (params.getInt("parallelism", 1) > 1) {
            // Distribute by MMSI for scaling
            // Broadcast feedback so all collector instances receive cleanup instructions
            notificationStream = mainStream
                .keyBy(e -> e.getValueOf("mmsi").toString())
                .connect(feedbackStream.broadcast()) 
                .process(new Collector(outputPath, namingPrefix, bucketSizeSec, lastK));
        } else {
            // Single global collector
            notificationStream = mainStream
                .global()
                .connect(feedbackStream.broadcast())
                .process(new Collector(outputPath, namingPrefix, bucketSizeSec, lastK));
        }

        if ("file".equalsIgnoreCase(inputSource)) {
            notificationStream.print("NOTIFICATION");
        } else {
            KafkaSink<String> datasetsSink = KafkaSink.<String>builder()
                .setBootstrapServers(kafkaServers)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(datasetsTopic)
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build())
                .build();
            notificationStream.sinkTo(datasetsSink).name("Unified Collector (Disk+Kafka)");
        }

        // =========================================================================
        // 5. Branch C: Wayeb Engine (Detection & Forecasting with Dynamic Models)
        // =========================================================================
        SingleOutputStreamOperator<ReportOutput> reportStream = mainStream
            .keyBy(e -> e.getValueOf("mmsi").toString())
            .connect(broadcastStream) 
            .process(new WayebEngine(
                loadPath,
                horizon,
                runConfidenceThreshold,
                maxSpread,
                reportingDistance
            ));

        DataStream<String> detectionStream = reportStream
            .getSideOutput(WayebEngine.MATCH_TAG);

        DataStream<PredictionOutput> predictionStream = reportStream
            .getSideOutput(WayebEngine.PRED_TAG);

        reportStream.print("LOCAL_REPORT");
        detectionStream.print("ALERT");
        predictionStream.print("FORECAST");

        // =========================================================================
        // 6. Branch D: In-Job Observer (Aggregation -> Decision)
        // =========================================================================
        
        // 1. GLOBAL AGGREGATION (Map-Reduce)
        DataStream<ReportOutput> globalReports = reportStream
            .windowAll(TumblingEventTimeWindows.of(Time.milliseconds(reportingDistance)))
            .reduce(new MetricsAggregator());
            
        globalReports.print("GLOBAL_REPORT");

        // 2. OBSERVER DECISION
        DataStream<String> instructions = globalReports
            .keyBy(r -> "GLOBAL") // Dummy key to access KeyedState in Observer
            .process(new ObserverProcess(
                obsTrainDiff, 
                obsOptDiff, 
                obsLowScore, 
                obsGrace
            ));

        instructions.print("INSTRUCTION");

        // 3. KAFKA SINK
        if ("file".equalsIgnoreCase(inputSource)) {
            // Bypass instructions sink in file mode
        } else {
            KafkaSink<String> instructionsSink = KafkaSink.<String>builder()
                .setBootstrapServers(kafkaServers)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(instructionsTopic)
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build())
                .build();
            instructions.sinkTo(instructionsSink).name("Observer (Instructions Sink)");
        }
        
        env.execute("RTCEF Inference Job");
    }
}
