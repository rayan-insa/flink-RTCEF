package edu.insa_lyon.streams.rtcef_flink;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;

import java.util.Properties;

public class ModelFactoryJob {

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
        String datasetsTopic = params.get("datasets-topic", "datasets");
        String outputTopic = params.get("output-topic", "factory_reports");
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
        // Key by constant to ensure all commands/datasets go to same operator instance
        DataStream<String> reportStream = commandStream
                .keyBy(value -> "factory-worker")
                .connect(datasetStream.keyBy(value -> "factory-worker"))
                .process(new ModelFactoryEngine());

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

        env.execute("Model Factory Job");
    }
}

