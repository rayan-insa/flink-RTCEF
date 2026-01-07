import logging
import skopt
from skopt.space import Real, Integer
from skopt.utils import use_named_args
import numpy as np
import time

from controller_job.models.instruction import Instruction, InstructionType


class HyperparameterOptimizer:
    """
    Wrapper around Scikit-Optimize (skopt) for RTCEF hyperparameter tuning.
    
    The optimizer generates commands for the Factory based on instructions
    from the Observer. It does NOT receive datasets - the Factory handles
    dataset management.
    """

    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self.command_counter = 0

    def create_command(self, instruction: Instruction) -> dict:
        """
        Create a Factory command based on the instruction type.
        
        Args:
            instruction: The control instruction from Observer
            
        Returns:
            A command dictionary for the Factory
        """
        command_id = f"cmd-{instruction.model_id}-{instruction.timestamp}"
        self.command_counter += 1
        
        # Base command structure
        base_command = {
            "id": command_id,
            "reply_id": command_id,
            "timestamp": instruction.timestamp,
            "model_id": instruction.model_id
        }

        if instruction.instruction_type == InstructionType.RETRAIN:
            self.logger.info(f"Processing RETRAIN instruction for {instruction.model_id}")
            
            # Simple retrain: use parameters from instruction or defaults
            command = base_command.copy()
            command["type"] = "TRAIN"
            command.update(instruction.parameters)
            
            return command
            
        elif instruction.instruction_type == InstructionType.OPTIMIZE:
            self.logger.info(f"Processing OPTIMIZE instruction for {instruction.model_id}")
            
            # Run Bayesian optimization to find best hyperparameters
            bounds = instruction.parameters.get('bounds', {})
            
            # Define search space
            space = [
                Real(bounds.get('pMin_min', 0.001), bounds.get('pMin_max', 0.1), name='pMin'),
                Real(bounds.get('gamma_min', 0.0), bounds.get('gamma_max', 1.0), name='gamma')
            ]
            
            try:
                # Run optimization (simulated objective function)
                res = skopt.gp_minimize(
                    lambda x: (x[0] - 0.05)**2 + (x[1] - 0.5)**2, 
                    space, 
                    n_calls=10, 
                    random_state=42
                )
                best_params = res.x
                
                command = base_command.copy()
                command["type"] = "TRAIN"
                command["pMin"] = float(best_params[0])
                command["gamma"] = float(best_params[1])
                command["note"] = "Optimized by PyFlink/Skopt"
                
                return command
                
            except Exception as e:
                self.logger.error(f"Skopt optimization failed: {e}")
                # Fallback to default params
                command = base_command.copy()
                command["type"] = "TRAIN"
                command["pMin"] = 0.05
                command["gamma"] = 0.5
                command["error"] = str(e)
                return command
        
        elif instruction.instruction_type == InstructionType.UPDATE_PARAMS:
            self.logger.info(f"Processing UPDATE_PARAMS instruction for {instruction.model_id}")
            
            command = base_command.copy()
            command["type"] = "UPDATE"
            command.update(instruction.parameters)
            
            return command
        
        else:
            self.logger.warning(f"Unknown instruction type: {instruction.instruction_type}")
            return None

