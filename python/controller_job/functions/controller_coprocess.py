"""
Controller CoProcessFunction - Central Logic for the "Brain"

Handles two streams:
1. Instructions from Observer (Kafka) -> Triggers actions (Optimize/Retrain)
2. Reports from Factory (Kafka) -> Provides feedback (Loss/Success) to update the Optimizer

This closes the feedback loop.
"""

from pyflink.datastream import KeyedCoProcessFunction
import json
import logging

from controller_job.models.instruction import Instruction, InstructionType
from controller_job.optimizer import HyperparameterOptimizer


class ControllerCoProcessFunction(KeyedCoProcessFunction):
    """
    KeyedCoProcessFunction that orchestrates the closed-loop optimization.
    
    Stream 1 (process_element1): Instructions from Observer
    Stream 2 (process_element2): Reports from Factory
    """

    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self.optimizer = None

    def open(self, runtime_context):
        """Initialize optimizer on function open."""
        self.optimizer = HyperparameterOptimizer()
        self.logger.info("ControllerCoProcessFunction initialized (Closed-Loop)")

    def process_element1(self, instruction: Instruction, ctx):
        """
        Process Instructions from Observer (Stream 1).
        Triggers new commands to the Factory.
        """
        self.logger.info(f"Received Instruction: {instruction.model_id} - {instruction.instruction_type}")
        
        try:
            # Generate command based on instruction + current optimizer state
            command = self.optimizer.create_command(instruction)
            
            if command:
                self.logger.info(f"Emitting command: {command.get('type')} for model {command.get('model_id')}")
                yield json.dumps(command)
            else:
                self.logger.warning("Optimizer returned no command for this instruction")
                
        except Exception as e:
            self.logger.error(f"Error processing instruction: {e}", exc_info=True)

    def process_element2(self, report_json: str, ctx):
        """
        Process Reports from Factory (Stream 2).
        Updates the Optimizer's internal state (Feedback).
        """
        try:
            report = json.loads(report_json)
            self.logger.info(f"Received Factory Report: {report.get('reply_id')} ({report.get('status')})")
            
            # Feed result back to the optimizer
            self.optimizer.update_model(report)
            
        except Exception as e:
            self.logger.error(f"Error processing report: {e}", exc_info=True)
