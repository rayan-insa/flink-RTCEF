"""
PyFlink Controller Job - Main Entry Point

Orchestrates model optimization and re-training based on:
- Instructions from Observer (Kafka: topic 'instructions')
- Datasets from Collector (Kafka: topic 'datasets')

Outputs:
- Updated models (Kafka: topic 'model-updates')
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

import logging
import sys

from controller_job.functions.controller_coprocess import ControllerCoProcessFunction
from controller_job.models.instruction import Instruction
from controller_job.models.dataset import TrainingDataset


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
    
    # Configure for Kafka sources/sinks
    # These JARs are required for Kafka connectors
    # Note: In production, these should be in the Flink lib/ folder or submitted with the job
    # TODO: Update paths based on deployment environment
    
    logger.info("Environment configured")
    
    # =========================================================================
    # 2. Kafka Configuration
    # =========================================================================
    # TODO: Move to configuration file or environment variables
    KAFKA_BOOTSTRAP_SERVERS = "localhost:9092"
    INSTRUCTION_TOPIC = "instructions"
    DATASET_TOPIC = "datasets"
    MODEL_OUTPUT_TOPIC = "model-updates"
    
    # =========================================================================
    # 3. Create Kafka Sources
    # =========================================================================
    logger.info("Setting up Kafka sources...")
    
    # Source 1: Instructions from Observer
    instruction_source = create_kafka_source(
        bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
        topic=INSTRUCTION_TOPIC,
        group_id="controller-instruction-consumer"
    )
    
    # Source 2: Datasets from Collector
    dataset_source = create_kafka_source(
        bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
        topic=DATASET_TOPIC,
        group_id="controller-dataset-consumer"
    )
    
    # =========================================================================
    # 4. Create Data Streams
    # =========================================================================
    logger.info("Creating data streams...")
    
    # Stream 1: Read instructions and deserialize
    instruction_stream = env.from_source(
        instruction_source,
        watermark_strategy=None,  # No watermarks needed for now
        source_name="Instruction Source"
    ).map(
        lambda json_str: Instruction.from_json(json_str),
        output_type=Types.PICKLED_BYTE_ARRAY()  # PyFlink will use pickle for complex objects
    )
    
    # Stream 2: Read datasets and deserialize
    dataset_stream = env.from_source(
        dataset_source,
        watermark_strategy=None,
        source_name="Dataset Source"
    ).map(
        lambda json_str: TrainingDataset.from_json(json_str),
        output_type=Types.PICKLED_BYTE_ARRAY()
    )
    
    # =========================================================================
    # 5. Connect Streams with CoProcessFunction
    # =========================================================================
    logger.info("Connecting streams with CoProcessFunction...")
    
    # Key both streams by model_id to ensure related data goes to the same parallel instance
    keyed_dataset_stream = dataset_stream.key_by(lambda ds: ds.metadata.get('model_id', 'default'))
    keyed_instruction_stream = instruction_stream.key_by(lambda inst: inst.model_id)
    
    # Connect the two streams
    connected_stream = keyed_dataset_stream.connect(keyed_instruction_stream)
    
    # Apply the CoProcessFunction
    model_output_stream = connected_stream.process(
        ControllerCoProcessFunction(),
        output_type=Types.STRING()  # Models will be serialized as strings
    )
    
    # =========================================================================
    # 6. Create Kafka Sink and Write Output
    # =========================================================================
    logger.info("Setting up Kafka sink...")
    
    model_sink = create_kafka_sink(
        bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
        topic=MODEL_OUTPUT_TOPIC
    )
    
    model_output_stream.sink_to(model_sink)
    
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
