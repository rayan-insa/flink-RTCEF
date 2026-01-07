"""
Mock Instruction Producer

Simulates the Observer sending instructions to Kafka.
Useful for testing the Controller Job without the full pipeline.
"""

from kafka import KafkaProducer
import json
import time
import logging
from typing import Dict, Any

from controller_job.models.instruction import Instruction, InstructionType


logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class MockInstructionProducer:
    """
    Mock producer that simulates Observer sending instructions
    """
    
    def __init__(self, bootstrap_servers: str = 'localhost:9092', topic: str = 'instructions'):
        """
        Initialize the mock producer
        
        Args:
            bootstrap_servers: Kafka broker address
            topic: Kafka topic to write to
        """
        self.topic = topic
        self.producer = KafkaProducer(
            bootstrap_servers=bootstrap_servers,
            value_serializer=lambda v: json.dumps(v).encode('utf-8')
        )
        logger.info(f"MockInstructionProducer initialized for topic '{topic}'")
    
    def send_instruction(
        self,
        instruction_type: InstructionType,
        model_id: str = "maritime_model_v1",
        parameters: Dict[str, Any] = None,
        metrics: Dict[str, float] = None
    ):
        """
        Send a mock instruction to Kafka
        
        Args:
            instruction_type: Type of instruction (optimize/retrain)
            model_id: ID of the model
            parameters: Additional parameters
            metrics: Performance metrics
        """
        instruction = Instruction(
            instruction_type=instruction_type,
            timestamp=int(time.time() * 1000),  # Unix timestamp in ms
            model_id=model_id,
            parameters=parameters or {},
            metrics=metrics or {}
        )
        
        # Serialize to JSON
        instruction_dict = {
            'instruction_type': instruction.instruction_type.value,
            'timestamp': instruction.timestamp,
            'model_id': instruction.model_id,
            'parameters': instruction.parameters,
            'metrics': instruction.metrics
        }
        
        # Send to Kafka
        future = self.producer.send(self.topic, instruction_dict)
        future.get(timeout=10)  # Wait for confirmation
        
        logger.info(f"Sent {instruction_type.value} instruction for model {model_id}")
    
    def send_optimize_instruction(self, model_id: str = "maritime_model_v1"):
        """Send an OPTIMIZE instruction"""
        self.send_instruction(
            instruction_type=InstructionType.OPTIMIZE,
            model_id=model_id,
            parameters={'learning_rate': 0.001, 'epochs': 10},
            metrics={'accuracy': 0.85, 'f1_score': 0.82}
        )
    
    def send_retrain_instruction(self, model_id: str = "maritime_model_v1"):
        """Send a RETRAIN instruction"""
        self.send_instruction(
            instruction_type=InstructionType.RETRAIN,
            model_id=model_id,
            parameters={'full_retrain': True},
            metrics={'accuracy': 0.75, 'drift_detected': True}
        )
    
    def close(self):
        """Close the producer"""
        self.producer.close()
        logger.info("MockInstructionProducer closed")


def main():
    """
    Example usage: Send some test instructions
    """
    producer = MockInstructionProducer()
    
    try:
        logger.info("Sending test instructions...")
        
        # Wait a bit for Kafka to be ready
        time.sleep(2)
        
        # Send an optimize instruction
        producer.send_optimize_instruction()
        time.sleep(1)
        
        # Send a retrain instruction
        producer.send_retrain_instruction()
        time.sleep(1)
        
        logger.info("Test instructions sent successfully")
        
    except Exception as e:
        logger.error(f"Error sending instructions: {e}", exc_info=True)
    finally:
        producer.close()


if __name__ == "__main__":
    main()
