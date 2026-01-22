"""
PyFlink Controller Job - Synchronous Optimization Mode

This controller implements the baseline-compatible synchronous optimization protocol:
1. Consumes instructions from Observer (Kafka: topic 'instructions')
2. Runs blocking optimization loops with Factory
3. Sends sync commands to Engine (pause/play)

Topics:
- Input: 'instructions' (from Observer)
- Input: 'factory_reports' (from Factory)
- Output: 'factory_commands' (to Factory)
- Output: 'enginesync' (to Engine)
"""

import json
import logging
import sys
import time
from typing import Optional, Dict
from threading import Thread, Event
from queue import Queue, Empty

from confluent_kafka import Consumer, Producer, KafkaError

from controller_job.models.instruction import Instruction, InstructionType
from controller_job.optimizer import HyperparameterOptimizer


# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    stream=sys.stdout
)
logger = logging.getLogger(__name__)


class SyncController:
    """
    Synchronous Controller that implements the baseline optimization protocol.
    
    Uses blocking waits for Factory reports instead of Flink streaming.
    """

    def __init__(
        self,
        bootstrap_servers: str = "kafka:29092",
        instruction_topic: str = "instructions",
        report_topic: str = "factory_reports",
        command_topic: str = "factory_commands",
        sync_topic: str = "enginesync",
        group_id: str = "controller-sync"
    ):
        self.bootstrap_servers = bootstrap_servers
        self.instruction_topic = instruction_topic
        self.report_topic = report_topic
        self.command_topic = command_topic
        self.sync_topic = sync_topic
        self.group_id = group_id
        
        # Kafka clients
        self.consumer: Optional[Consumer] = None
        self.producer: Optional[Producer] = None
        self.report_consumer: Optional[Consumer] = None
        
        # Optimizer
        self.optimizer = HyperparameterOptimizer(
            n_initial_points=5,
            n_total_evals=10,
            seed=42
        )
        
        # State
        self.running = False
        self.stop_event = Event()

    def _create_consumer(self, topics: list, group_suffix: str = "") -> Consumer:
        """Create a Kafka consumer for the given topics."""
        config = {
            'bootstrap.servers': self.bootstrap_servers,
            'group.id': f"{self.group_id}{group_suffix}",
            'auto.offset.reset': 'latest',
            'enable.auto.commit': True
        }
        consumer = Consumer(config)
        consumer.subscribe(topics)
        logger.info(f"Created consumer for topics: {topics}")
        return consumer

    def _create_producer(self) -> Producer:
        """Create a Kafka producer."""
        config = {
            'bootstrap.servers': self.bootstrap_servers,
            'acks': 'all'
        }
        producer = Producer(config)
        logger.info("Created Kafka producer")
        return producer

    def _send_command(self, command: Dict):
        """Send a command to the Factory via Kafka."""
        msg = json.dumps(command)
        self.producer.produce(self.command_topic, value=msg.encode('utf-8'))
        self.producer.flush()
        logger.info(f"Sent command to {self.command_topic}: type={command.get('type')}, id={command.get('id')}")

    def _send_sync(self, sync_msg: str):
        """Send a sync command to the Engine via Kafka."""
        self.producer.produce(self.sync_topic, value=sync_msg.encode('utf-8'))
        self.producer.flush()
        logger.info(f"Sent sync command to {self.sync_topic}")

    def _wait_for_report(self, timeout: float = 300.0) -> Dict:
        """
        Block until a Factory report arrives.
        
        Args:
            timeout: Maximum time to wait in seconds
            
        Returns:
            Parsed report dictionary
        """
        start_time = time.time()
        
        while time.time() - start_time < timeout:
            if self.stop_event.is_set():
                raise InterruptedError("Controller stopped while waiting for report")
            
            msg = self.report_consumer.poll(timeout=1.0)
            
            if msg is None:
                continue
            
            if msg.error():
                if msg.error().code() == KafkaError._PARTITION_EOF:
                    continue
                logger.error(f"Kafka error: {msg.error()}")
                continue
            
            try:
                report = json.loads(msg.value().decode('utf-8'))
                logger.info(f"Received Factory report: reply_id={report.get('reply_id')}")
                return report
            except json.JSONDecodeError as e:
                logger.error(f"Failed to parse report: {e}")
                continue
        
        raise TimeoutError(f"Timed out waiting for Factory report after {timeout}s")

    def _process_instruction(self, instruction: Instruction):
        """Process a single instruction from the Observer."""
        logger.info(f"Processing instruction: {instruction.instruction_type.value} for {instruction.model_id}")
        
        timestamp = instruction.timestamp
        
        if instruction.instruction_type == InstructionType.OPTIMIZE:
            # Run full synchronous optimization loop
            model_id = self.optimizer.run_optimization(
                timestamp=timestamp,
                send_command=self._send_command,
                wait_for_report=self._wait_for_report,
                send_sync=self._send_sync
            )
            logger.info(f"Optimization complete: deployed model_id={model_id}")
            
        elif instruction.instruction_type == InstructionType.RETRAIN:
            # Run simple train
            params = instruction.parameters if instruction.parameters else None
            model_id = self.optimizer.run_train(
                timestamp=timestamp,
                params=params,
                send_command=self._send_command,
                wait_for_report=self._wait_for_report,
                send_sync=self._send_sync
            )
            logger.info(f"Retrain complete: deployed model_id={model_id}")
            
        elif instruction.instruction_type == InstructionType.UPDATE_PARAMS:
            # Just update local state
            self.optimizer.current_best_params.update(instruction.parameters)
            logger.info(f"Updated best params: {self.optimizer.current_best_params}")

    def run(self):
        """Main run loop - consume instructions and process them."""
        logger.info("=== Starting Synchronous Controller ===")
        
        # Initialize Kafka clients
        self.consumer = self._create_consumer([self.instruction_topic])
        self.report_consumer = self._create_consumer([self.report_topic], group_suffix="-reports")
        self.producer = self._create_producer()
        
        self.running = True
        logger.info(f"Listening on topic: {self.instruction_topic}")
        
        try:
            while not self.stop_event.is_set():
                msg = self.consumer.poll(timeout=1.0)
                
                if msg is None:
                    continue
                
                if msg.error():
                    if msg.error().code() == KafkaError._PARTITION_EOF:
                        continue
                    logger.error(f"Kafka error: {msg.error()}")
                    continue
                
                try:
                    instruction = Instruction.from_json(msg.value().decode('utf-8'))
                    self._process_instruction(instruction)
                except Exception as e:
                    logger.error(f"Error processing instruction: {e}", exc_info=True)
                    
        except KeyboardInterrupt:
            logger.info("Received shutdown signal")
        finally:
            self.stop()

    def stop(self):
        """Stop the controller gracefully."""
        logger.info("Stopping controller...")
        self.stop_event.set()
        self.running = False
        
        if self.consumer:
            self.consumer.close()
        if self.report_consumer:
            self.report_consumer.close()
        if self.producer:
            self.producer.flush()
        
        logger.info("Controller stopped")


def main():
    """Main entry point."""
    controller = SyncController(
        bootstrap_servers="kafka:29092",
        instruction_topic="instructions",
        report_topic="factory_reports",
        command_topic="factory_commands",
        sync_topic="enginesync"
    )
    controller.run()


if __name__ == "__main__":
    main()
