"""
PyFlink Controller Job - Flink-Native State Machine Mode.

This module implements the synchronous optimization protocol as an event-driven
Flink job. It manages the lifecycle of optimization sessions by connecting
observer instructions and factory reports through a stateful co-process function.

Topics:
- Input: 'instructions' (from Observer)
- Input: 'factory_reports' (from Factory)
- Output: 'factory_commands' (to Factory)
- Output: 'enginesync' (to Engine)
"""

import json
import logging
import os
import sys

from pyflink.datastream import StreamExecutionEnvironment
from pyflink.datastream.connectors.kafka import (
    KafkaSource,
    KafkaRecordSerializationSchema,
    KafkaSink,
    DeliveryGuarantee
)
from pyflink.datastream.formats.json import JsonRowDeserializationSchema
from pyflink.common import WatermarkStrategy, Types
from pyflink.common.serialization import SimpleStringSchema

from controller_job.models.instruction import Instruction
from controller_job.functions.controller_coprocess import (
    ControllerCoProcessFunction,
    FACTORY_COMMAND_TAG,
    ENGINE_SYNC_TAG
)


# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    stream=sys.stdout
)
logger = logging.getLogger(__name__)


def create_kafka_source(
    env: StreamExecutionEnvironment,
    bootstrap_servers: str,
    topic: str,
    group_id: str
):
    """
    Create a Kafka source with string deserialization.
    
    Args:
        env: The PyFlink stream execution environment.
        bootstrap_servers: Kafka bootstrap server string.
        topic: The Kafka topic to subscribe to.
        group_id: Kafka consumer group ID.
        
    Returns:
        DataStream: A stream of strings from Kafka.
    """
    source = KafkaSource.builder() \
        .set_bootstrap_servers(bootstrap_servers) \
        .set_topics(topic) \
        .set_group_id(group_id) \
        .set_starting_offsets(
            # Use earliest for instructions to not miss any
            # Use latest for reports since we only care about new ones
            # For simplicity, use earliest for both during development
            __import__('pyflink.datastream.connectors.kafka', 
                       fromlist=['KafkaOffsetsInitializer']).KafkaOffsetsInitializer.earliest()
        ) \
        .set_value_only_deserializer(SimpleStringSchema()) \
        .build()
    
    return env.from_source(
        source,
        WatermarkStrategy.no_watermarks().with_idleness(
            __import__('pyflink.common', fromlist=['Duration']).Duration.of_seconds(60)
        ),
        f"kafka-source-{topic}"
    )


def create_kafka_sink(
    bootstrap_servers: str,
    topic: str
):
    """
    Create a Kafka sink with string serialization.
    
    Args:
        bootstrap_servers: Kafka bootstrap server string.
        topic: The destination Kafka topic.
        
    Returns:
        KafkaSink: A configured Kafka sink.
    """
    return KafkaSink.builder() \
        .set_bootstrap_servers(bootstrap_servers) \
        .set_record_serializer(
            KafkaRecordSerializationSchema.builder()
            .set_topic(topic)
            .set_value_serialization_schema(SimpleStringSchema())
            .build()
        ) \
        .set_delivery_guarantee(DeliveryGuarantee.AT_LEAST_ONCE) \
        .build()


def parse_instruction(json_str: str) -> Instruction:
    """
    Parse instruction JSON to Instruction object.
    
    Args:
        json_str: The raw JSON string from Kafka.
        
    Returns:
        Instruction: The parsed instruction model.
    """
    return Instruction.from_json(json_str)


def main():
    """Main entry point for the Flink Controller Job."""
    # Configuration from environment
    bootstrap_servers = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka:29092")
    instruction_topic = os.getenv("INSTRUCTION_TOPIC", "instructions")
    report_topic = os.getenv("REPORT_TOPIC", "factory_reports")
    command_topic = os.getenv("COMMAND_TOPIC", "factory_commands")
    sync_topic = os.getenv("SYNC_TOPIC", "enginesync")
    
    logger.info("=== Starting Flink Controller Job (State Machine Mode) ===")
    logger.info(f"Kafka: {bootstrap_servers}")
    logger.info(f"Input topics: {instruction_topic}, {report_topic}")
    logger.info(f"Output topics: {command_topic}, {sync_topic}")
    
    # Create execution environment
    env = StreamExecutionEnvironment.get_execution_environment()
    env.set_parallelism(1)  # Single partition for global ordering
    
    # Enable checkpointing for fault tolerance
    env.enable_checkpointing(10000)  # 10 second checkpoint interval
    
    # Create Kafka sources
    instruction_stream = create_kafka_source(
        env, bootstrap_servers, instruction_topic, "controller-instructions"
    )
    
    report_stream = create_kafka_source(
        env, bootstrap_servers, report_topic, "controller-reports"
    )
    
    # Parse instructions
    parsed_instructions = instruction_stream \
        .map(parse_instruction, output_type=Types.PICKLED_BYTE_ARRAY()) \
        .name("parse-instructions")
    
    # Key both streams by a constant key (single optimizer instance)
    # In a multi-tenant setup, you would key by model_id or tenant_id
    keyed_instructions = parsed_instructions \
        .key_by(lambda instr: "GLOBAL", key_type=Types.STRING())
    
    keyed_reports = report_stream \
        .key_by(lambda report: "GLOBAL", key_type=Types.STRING())
    
    # Connect and process with state machine
    controller = ControllerCoProcessFunction()
    
    processed = keyed_instructions \
        .connect(keyed_reports) \
        .process(controller) \
        .name("controller-state-machine")
    
    # Get side outputs
    factory_commands = processed.get_side_output(FACTORY_COMMAND_TAG)
    engine_sync = processed.get_side_output(ENGINE_SYNC_TAG)
    
    # Create Kafka sinks
    command_sink = create_kafka_sink(bootstrap_servers, command_topic)
    sync_sink = create_kafka_sink(bootstrap_servers, sync_topic)
    
    # Connect to sinks
    factory_commands.sink_to(command_sink).name("sink-factory-commands")
    engine_sync.sink_to(sync_sink).name("sink-engine-sync")
    
    # Main output (optimization completion events) - log for now
    processed.print().name("log-completion-events")
    
    logger.info("Job graph constructed, executing...")
    env.execute("Controller Job - State Machine")


if __name__ == "__main__":
    main()
