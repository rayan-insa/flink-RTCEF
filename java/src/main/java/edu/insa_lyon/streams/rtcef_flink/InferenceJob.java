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
        final String modelReportsTopic = params.get("model-reports-topic", "factory_reports");

        // Construct full path prefix for notifications
        final String pathPrefix = outputPath + "/" + namingPrefix;

        System.out.println("=== InferenceJob Configuration ===");
        System.out.println("Initial Model Path: " + loadPath);
        System.out.println("Input Source: " + inputSource);
        if ("file".equalsIgnoreCase(inputSource)) System.out.println("Input Path: " + inputPath);
        else System.out.println("Input Topic: " + inputTopic);
        System.out.println("Horizon: " + horizon);
        System.out.println("Threshold: " + runConfidenceThreshold);
        System.out.println("Max Spread: " + maxSpread);
        System.out.println("--- Collector ---");
        System.out.println("Output Path: " + outputPath);
        System.out.println("Bucket Size (sec): " + bucketSizeSec);
        System.out.println("Last K: " + lastK);
        System.out.println("--- Kafka ---");
        System.out.println("Kafka Servers: " + kafkaServers);
        System.out.println("Datasets Topic: " + datasetsTopic);
        System.out.println("Model Reports Topic: " + modelReportsTopic);
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

        // B. Model Updates (Kafka)
        KafkaSource<String> modelSource = KafkaSource.<String>builder()
                .setBootstrapServers(kafkaServers)
                .setTopics(modelReportsTopic)
                .setGroupId("inference-group-models")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<String> modelUpdateStream = env.fromSource(
                modelSource, 
                WatermarkStrategy.noWatermarks(), 
                "Model Reports Source"
        );
        
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

        KafkaSink<String> kafkaSink = KafkaSink.<String>builder()
                .setBootstrapServers(kafkaServers)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(datasetsTopic)
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build())
                .build();

        notificationStream.sinkTo(kafkaSink).name("Dataset Notifier (Kafka)");

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
                maxSpread
            ));

        DataStream<String> detectionStream = reportStream
            .getSideOutput(WayebEngine.MATCH_TAG);

        DataStream<PredictionOutput> predictionStream = reportStream
            .getSideOutput(WayebEngine.PRED_TAG);

        reportStream.print("REPORT");
        detectionStream.print("ALERT");
        predictionStream.print("FORECAST");

        env.execute("RTCEF Inference Job");
    }
}
