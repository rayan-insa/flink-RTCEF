"""
Controller ProcessFunction - Handles Instructions from Observer

This is a simplified KeyedProcessFunction (not CoProcess) that:
- Receives Instructions from the Observer
- Runs optimization logic to determine best hyperparameters
- Emits commands to the Factory

The Controller does NOT consume datasets directly.
Datasets are handled by the Factory.
"""

from pyflink.datastream import KeyedProcessFunction
import json
import logging

from controller_job.models.instruction import Instruction, InstructionType
from controller_job.optimizer import HyperparameterOptimizer


class ControllerProcessFunction(KeyedProcessFunction):
    """
    KeyedProcessFunction that handles Instructions from Observer.
    
    For each instruction:
    - OPTIMIZE: Run Bayesian optimization to find best hyperparameters
    - RETRAIN: Generate a train command with current best parameters
    
    Outputs commands to factory_commands topic.
    """

    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self.optimizer = None

    def open(self, runtime_context):
        """Initialize optimizer on function open."""
        self.optimizer = HyperparameterOptimizer()
        self.logger.info("ControllerProcessFunction initialized")

    def process_element(self, instruction: Instruction, ctx):
        """
        Process an incoming Instruction.
        
        Args:
            instruction: The instruction from Observer
            ctx: Processing context
            
        Yields:
            JSON string commands for the Factory
        """
        self.logger.info(f"Received Instruction: {instruction.model_id} - {instruction.instruction_type}")
        
        try:
            # Generate command based on instruction type
            command = self.optimizer.create_command(instruction)
            
            if command:
                self.logger.info(f"Emitting command: {command.get('type')} for model {command.get('model_id')}")
                yield json.dumps(command)
            else:
                self.logger.warning("Optimizer returned no command")
                
        except Exception as e:
            self.logger.error(f"Error processing instruction: {e}", exc_info=True)
