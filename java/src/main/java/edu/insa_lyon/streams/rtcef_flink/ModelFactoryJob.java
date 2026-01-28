package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.util.OutputTag;

import java.util.Properties;

/**
 * Flink Job for training and optimizing Prediction Suffix Trees (PST).
 * 
 * This job coordinates with the Python Controller to perform hyperparameter 
 * optimization (Bayesian search) and model re-training.
 */
public class ModelFactoryJob {

    /**
     * Main entry point for the Model Factory Job.
     * 
     * @param args Command line arguments.
     * @throws Exception If job execution fails.
     */
    public static void main(String[] args) throws Exception {
        final ParameterTool params = ParameterTool.fromArgs(args);
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Check required parameters
        if (!params.has("kafka-servers")) {
            System.err.println("Usage: ModelFactoryJob --kafka-servers <servers> [--commands-topic <topic>] [--datasets-topic <topic>] [--output-topic <topic>]");
            return;
        }

        String kafkaServers = params.get("kafka-servers");
        String commandsTopic = params.get("commands-topic", "factory_commands");
        String datasetsTopic = params.get("datasets-topic", "dataset_versions");
        String outputTopic = params.get("output-topic", "model_reports");
        String groupId = params.get("group-id", "flink-factory-group");

        // =========================================================================
        // Kafka Source 1: Commands from Controller
        // =========================================================================
        KafkaSource<String> commandsSource = KafkaSource.<String>builder()
                .setBootstrapServers(kafkaServers)
                .setTopics(commandsTopic)
                .setGroupId(groupId + "-commands")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<String> commandStream = env.fromSource(
                commandsSource, 
                WatermarkStrategy.noWatermarks(), 
                "Factory Commands Source"
        );

        // =========================================================================
        // Kafka Source 2: Datasets from Collector
        // =========================================================================
        KafkaSource<String> datasetsSource = KafkaSource.<String>builder()
                .setBootstrapServers(kafkaServers)
                .setTopics(datasetsTopic)
                .setGroupId(groupId + "-datasets")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<String> datasetStream = env.fromSource(
                datasetsSource, 
                WatermarkStrategy.noWatermarks(), 
                "Factory Datasets Source"
        );

        // =========================================================================
        // Connect Streams and Apply CoProcessFunction
        // =========================================================================
        // Create OutputTag for Side Output
        final OutputTag<String> assemblyTag = new OutputTag<String>("assembly-reports"){};

        int horizon = params.getInt("horizon", 600);
        double runConfidenceThreshold = params.getDouble("threshold", 0.3);
        int maxSpread = params.getInt("maxSpread", 5);

        // Key by constant to ensure all commands/datasets go to same operator instance
        SingleOutputStreamOperator<String> reportStream = commandStream
                .keyBy(value -> "factory-worker")
                .connect(datasetStream.keyBy(value -> "factory-worker"))
                .process(new ModelFactoryEngine(assemblyTag, horizon, runConfidenceThreshold, maxSpread));

        // =========================================================================
        // Kafka Sink for Reports
        // =========================================================================
        KafkaSink<String> sink = KafkaSink.<String>builder()
                .setBootstrapServers(kafkaServers)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(outputTopic)
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build()
                )
                .build();

        reportStream.sinkTo(sink);

        // =========================================================================
        // Kafka Sink for Assembly Reports (Feedback Loop)
        // =========================================================================
        String assemblyTopic = params.get("assembly-topic", "assembly_reports");
        
        KafkaSink<String> assemblySink = KafkaSink.<String>builder()
                .setBootstrapServers(kafkaServers)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic(assemblyTopic)
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build()
                )
                .build();
                
        reportStream.getSideOutput(assemblyTag)
                .sinkTo(assemblySink);

        env.execute("Model Factory Job");
    }
}

