"""
PyFlink Controller Job - Main Entry Point

Orchestrates model optimization and re-training based on:
- Instructions from Observer (Kafka: topic 'instructions')

Outputs:
- Commands for Factory (Kafka: topic 'factory_commands')

Note: Datasets are consumed directly by the Factory, NOT by the Controller.
"""

from pyflink.datastream import StreamExecutionEnvironment
from pyflink.datastream.connectors.kafka import (
    KafkaSource,
    KafkaSink,
    KafkaRecordSerializationSchema,
    KafkaOffsetsInitializer
)
from pyflink.common.serialization import SimpleStringSchema
from pyflink.common.typeinfo import Types
from pyflink.common.watermark_strategy import WatermarkStrategy

import logging
import sys

from controller_job.functions.controller_coprocess import ControllerCoProcessFunction
from controller_job.models.instruction import Instruction


# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    stream=sys.stdout
)
logger = logging.getLogger(__name__)


def create_kafka_source(
    bootstrap_servers: str,
    topic: str,
    group_id: str
) -> KafkaSource:
    """
    Create a Kafka source connector
    
    Args:
        bootstrap_servers: Kafka broker addresses (e.g., 'localhost:9092')
        topic: Kafka topic name
        group_id: Consumer group ID
        
    Returns:
        Configured KafkaSource
    """
    return KafkaSource.builder() \
        .set_bootstrap_servers(bootstrap_servers) \
        .set_topics(topic) \
        .set_group_id(group_id) \
        .set_starting_offsets(KafkaOffsetsInitializer.latest()) \
        .set_value_only_deserializer(SimpleStringSchema()) \
        .build()


def create_kafka_sink(
    bootstrap_servers: str,
    topic: str
) -> KafkaSink:
    """
    Create a Kafka sink connector
    
    Args:
        bootstrap_servers: Kafka broker addresses
        topic: Kafka topic name
        
    Returns:
        Configured KafkaSink
    """
    serialization_schema = KafkaRecordSerializationSchema.builder() \
        .set_topic(topic) \
        .set_value_serialization_schema(SimpleStringSchema()) \
        .build()
    
    return KafkaSink.builder() \
        .set_bootstrap_servers(bootstrap_servers) \
        .set_record_serializer(serialization_schema) \
        .build()


def main():
    """
    Main function to set up and execute the PyFlink Controller Job
    """
    logger.info("=== Starting PyFlink Controller Job ===")
    
    # =========================================================================
    # 1. Environment Setup
    # =========================================================================
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_parallelism(1)  # Start with single parallelism for simplicity
    
    logger.info("Environment configured")
    
    # =========================================================================
    # 2. Kafka Configuration
    # =========================================================================
    KAFKA_BOOTSTRAP_SERVERS = "kafka:29092"
    INSTRUCTION_TOPIC = "instructions"
    COMMAND_OUTPUT_TOPIC = "factory_commands"
    
    # =========================================================================
    # 3. Create Kafka Source for Instructions
    # =========================================================================
    logger.info("Setting up Kafka source for instructions...")
    
    instruction_source = create_kafka_source(
        bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
        topic=INSTRUCTION_TOPIC,
        group_id="controller-instruction-consumer"
    )
    
    # =========================================================================
    # 4. Create Data Stream
    # =========================================================================
    logger.info("Creating instruction stream...")
    
    # Read instructions and deserialize
    instruction_stream = env.from_source(
        instruction_source,
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="Instruction Source"
    ).map(
        lambda json_str: Instruction.from_json(json_str),
        output_type=Types.PICKLED_BYTE_ARRAY()
    )
    
    # =========================================================================
    # 5. Connect Streams and Apply CoProcessFunction
    # =========================================================================
    logger.info("Setting up Kafka source for Factory Reports...")
    
    REPORT_TOPIC = "factory_reports"
    
    report_source = create_kafka_source(
        bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
        topic=REPORT_TOPIC,
        group_id="controller-report-consumer"
    )
    
    report_stream = env.from_source(
        report_source,
        watermark_strategy=WatermarkStrategy.no_watermarks(),
        source_name="Report Source"
    )
    
    logger.info("Connecting Instructions and Reports...")
    
    # Key both streams by model_id (assuming report contains model_id in JSON body)
    # Note: For simplicity, we key instructions by object attr and reports by a lambda parsing JSON
    # A more robust approach would be to map reports to a strongly typed object first.
    
    def parse_model_id_from_report(json_str: str) -> str:
        try:
            import json
            data = json.loads(json_str)
            return data.get("model_id", "unknown")
        except:
            return "unknown"

    command_stream = instruction_stream \
        .connect(report_stream) \
        .key_by(
            lambda inst: inst.model_id,               # Key selector for Stream 1 (Instruction)
            parse_model_id_from_report                # Key selector for Stream 2 (String/JSON Report)
        ) \
        .process(
            ControllerCoProcessFunction(),            # The logic that merges both
            output_type=Types.STRING()
        )
    
    # =========================================================================
    # 6. Create Kafka Sink and Write Output
    # =========================================================================
    logger.info("Setting up Kafka sink for commands...")
    
    command_sink = create_kafka_sink(
        bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
        topic=COMMAND_OUTPUT_TOPIC
    )
    
    command_stream.sink_to(command_sink)
    
    # =========================================================================
    # 7. Execute the Job
    # =========================================================================
    logger.info("Submitting job to Flink cluster...")
    
    try:
        env.execute("PyFlink Controller Job")
    except Exception as e:
        logger.error(f"Job execution failed: {e}", exc_info=True)
        raise


if __name__ == "__main__":
    main()

