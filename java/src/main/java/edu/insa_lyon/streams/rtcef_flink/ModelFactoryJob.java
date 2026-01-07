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
            System.err.println("Usage: ModelFactoryJob --kafka-servers <servers> --input-topic <topic> --output-topic <topic>");
            return;
        }

        String kafkaServers = params.get("kafka-servers");
        String inputTopic = params.get("input-topic", "factory_commands");
        String outputTopic = params.get("output-topic", "factory_reports");
        String outputModelsTopic = params.get("output-models-topic", "model_versions");
        String groupId = params.get("group-id", "flink-factory-group");

        // Kafka Source for Commands
        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(kafkaServers)
                .setTopics(inputTopic)
                .setGroupId(groupId)
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<String> commandStream = env.fromSource(source, WatermarkStrategy.noWatermarks(), "Factory Commands Source");

        DataStream<String> reportStream = commandStream
                .keyBy(value -> "factory-worker") // Keying by constant for simple parallelism control (1 worker)
                .process(new WayebTrainingEngine());

        // Kafka Sink for Reports
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
