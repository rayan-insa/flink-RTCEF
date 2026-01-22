package edu.insa_lyon.streams.rtcef_flink;

import java.time.Duration;

import org.apache.flink.core.fs.Path;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.connector.file.sink.FileSink;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.DefaultRollingPolicy;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.functions.sink.DiscardingSink;

import stream.GenericEvent;

import edu.insa_lyon.streams.rtcef_flink.utils.DynamicBucketAssigner;
import edu.insa_lyon.streams.rtcef_flink.utils.GenericEventEncoder;
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
    
    // Descriptor for Broadcast State (Model updates)
    public static final MapStateDescriptor<String, String> MODEL_UPDATE_DESCRIPTOR = 
        new MapStateDescriptor<>("modelUpdates", Types.STRING, Types.STRING);

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // =========================================================================
        // 1. Parse Parameters
        // =========================================================================
        ParameterTool params = ParameterTool.fromArgs(args);
        env.getConfig().setGlobalJobParameters(params);

        // Wayeb Engine Params
        String loadPath = params.get("modelPath", "/opt/flink/data/saved_models/tmp.spst");
        String inputSource = params.get("inputSource", "kafka"); // "file" or "kafka"
        String inputPath = params.get("inputPath", "/opt/flink/data/maritime.csv");
        String inputTopic = params.get("inputTopic", "inputstream");
        
        int horizon = params.getInt("horizon", 600);
        double runConfidenceThreshold = params.getDouble("threshold", 0.3);
        int maxSpread = params.getInt("maxSpread", 5);

        // Collector Params
        String outputPath = params.get("outputPath", "/opt/flink/data/saved_datasets");
        final long bucketSizeSec = params.getLong("bucketSize", 86400); 
        final String namingPrefix = params.get("naming", "dataset_");

        // Kafka / Notification Params
        final int lastK = params.getInt("lastK", 3);
        final String kafkaServers = params.get("kafka-servers", "kafka:29092");
        final String datasetsTopic = params.get("datasets-topic", "datasets");
        
        // Observer Params
        final String modelReportsTopic = params.get("model-reports-topic", "factory_reports");
        final long reportingDistance = params.getLong("reportingDistance", 600000); // 10 min
        final double obsTrainDiff = params.getDouble("observer-train-diff", 0.05);
        final double obsOptDiff = params.getDouble("observer-optimize-diff", 0.10);
        final double obsLowScore = params.getDouble("observer-low-score", 0.5);
        final int obsGrace = params.getInt("observer-grace", 3);
        final String instructionsTopic = params.get("instructions-topic", "instructions");

        // Construct full path prefix for notifications
        final String pathPrefix = outputPath + "/" + namingPrefix;

        System.out.println("=== InferenceJob Configuration ===");
        System.out.println("Input Source: " + inputSource);
        System.out.println("--- Observer ---");
        System.out.println("Reporting Distance (ms): " + reportingDistance);
        System.out.println("==================================");

        // =========================================================================
        // 2. Setup Data Sources
        // =========================================================================
        
        // A. Maritime Events
        DataStream<GenericEvent> mainStream;
        if ("file".equalsIgnoreCase(inputSource)) {
            FileSource<String> fileSource = FileSource
                .forRecordStreamFormat(new TextLineInputFormat(), new Path(inputPath))
                .build();
            mainStream = env
                .fromSource(fileSource, WatermarkStrategy.noWatermarks(), "Maritime File Source")
                .flatMap(new MaritimeParser());
        } else {
            KafkaSource<String> maritimeSource = KafkaSource.<String>builder()
                .setBootstrapServers(kafkaServers)
                .setTopics(inputTopic)
                .setGroupId("inference-group-maritime")
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();
            mainStream = env
                .fromSource(maritimeSource, WatermarkStrategy.noWatermarks(), "Maritime Kafka Source")
                .flatMap(new MaritimeParser());
        }

        mainStream = mainStream.assignTimestampsAndWatermarks(
            WatermarkStrategy.<GenericEvent>forBoundedOutOfOrderness(Duration.ofSeconds(60))
            .withTimestampAssigner((event, timestamp) -> event.timestamp() * 1000L)
        );

        // B. Model Updates + Sync Commands (Kafka or Dummy)
        DataStream<String> modelUpdateStream;
        final String engineSyncTopic = params.get("enginesync-topic", "enginesync");
        
        if ("file".equalsIgnoreCase(inputSource)) {
            // Bypass Kafka for Model Updates in File mode
             modelUpdateStream = env.fromElements("dummy").filter(x -> false); 
        } else {
            // Consume from both factory_reports (model updates) and enginesync (pause/play)
            KafkaSource<String> modelSource = KafkaSource.<String>builder()
                .setBootstrapServers(kafkaServers)
                .setTopics(modelReportsTopic, engineSyncTopic) // Multi-topic subscription
                .setGroupId("inference-group-models")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();
            modelUpdateStream = env.fromSource(modelSource, WatermarkStrategy.noWatermarks(), "Model+Sync Source");
        }
        
        // --- Broadcast for Dynamic Models ---
        BroadcastStream<String> broadcastStream = modelUpdateStream.broadcast(MODEL_UPDATE_DESCRIPTOR);

        // =========================================================================
        // 3. Branch A: Collector (FileSink)
        // =========================================================================
        DataStream<Tuple2<String, GenericEvent>> bucketedStream = mainStream
                .map(event -> {
                    long bucketStart = event.timestamp() - (event.timestamp() % bucketSizeSec);
                    String bucketName = namingPrefix + bucketStart; 
                    return Tuple2.of(bucketName, event);
                })
                .returns(Types.TUPLE(Types.STRING, Types.GENERIC(GenericEvent.class)));

        FileSink<Tuple2<String, GenericEvent>> fileSink = FileSink
                .<Tuple2<String, GenericEvent>>forRowFormat(new Path(outputPath), new GenericEventEncoder())
                .withBucketAssigner(new DynamicBucketAssigner())
                .withRollingPolicy(
                        DefaultRollingPolicy.builder()
                                .withInactivityInterval(Duration.ofMinutes(60))
                                .withMaxPartSize(1024 * 1024 * 128)
                                .build())
                .build();

        bucketedStream.sinkTo(fileSink).name("Dataset Collector (FileSink)");

        // =========================================================================
        // 4. Branch B: Dataset Notifier (Kafka)
        // =========================================================================
        DataStream<String> notificationStream = mainStream
                .windowAll(TumblingEventTimeWindows.of(Time.seconds(bucketSizeSec)))
                .process(new DatasetNotificationProcess(lastK, pathPrefix));

        if ("file".equalsIgnoreCase(inputSource)) {
            notificationStream.print("NOTIFICATION");
        } else {
            KafkaSink<String> kafkaSink = KafkaSink.<String>builder()
                .setBootstrapServers(kafkaServers)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(datasetsTopic)
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build())
                .build();
            notificationStream.sinkTo(kafkaSink).name("Dataset Notifier (Kafka)");
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
