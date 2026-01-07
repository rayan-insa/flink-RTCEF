"""
Controller CoProcess Function

Synchronizes two Kafka streams:
1. Instructions from Observer
2. Datasets from Collector

Implements the optimization/re-training loop.
"""

from pyflink.datastream import CoProcessFunction, RuntimeContext
from pyflink.datastream.state import ValueStateDescriptor, ListStateDescriptor

from typing import Optional
import logging

from controller_job.models.instruction import Instruction, InstructionType
from controller_job.models.dataset import TrainingDataset


logger = logging.getLogger(__name__)


class ControllerCoProcessFunction(CoProcessFunction):
    """
    CoProcessFunction that synchronizes Instructions and Datasets
    
    Stream 1 (processElement1): Datasets from Collector
    Stream 2 (processElement2): Instructions from Observer
    
    State Management:
    - Stores latest datasets (buffering until instruction arrives)
    - Triggers optimization/re-training when instruction received
    """
    
    def __init__(self):
        """Initialize the CoProcessFunction"""
        # State will be initialized in open()
        self.dataset_buffer_state = None
        self.last_instruction_state = None
        
    def open(self, runtime_context: RuntimeContext):
        """
        Initialize state when the function starts
        
        Called once per parallel instance (TaskManager)
        """
        # Buffer to store datasets until an instruction arrives
        # We use ListState to accumulate multiple datasets
        dataset_descriptor = ListStateDescriptor(
            "dataset_buffer",
            TrainingDataset  # Type hint for serialization
        )
        self.dataset_buffer_state = runtime_context.get_list_state(dataset_descriptor)
        
        # Store the last instruction for reference
        instruction_descriptor = ValueStateDescriptor(
            "last_instruction",
            Instruction
        )
        self.last_instruction_state = runtime_context.get_state(instruction_descriptor)
        
        logger.info("ControllerCoProcessFunction opened successfully")
    
    def process_element1(self, dataset: TrainingDataset, ctx: 'CoProcessFunction.Context'):
        """
        Process incoming Dataset from Collector (Stream 1)
        
        Strategy: Buffer datasets until an instruction arrives
        
        Args:
            dataset: Training dataset from Collector
            ctx: Flink context
        """
        logger.info(f"Received dataset: {dataset.dataset_id} with {dataset.size()} events")
        
        # Add dataset to buffer
        self.dataset_buffer_state.add(dataset)
        
        # Log current buffer size
        buffer = list(self.dataset_buffer_state.get())
        logger.debug(f"Dataset buffer now contains {len(buffer)} datasets")
        
        # Note: We don't trigger anything here. We wait for an Instruction.
    
    def process_element2(self, instruction: Instruction, ctx: 'CoProcessFunction.Context'):
        """
        Process incoming Instruction from Observer (Stream 2)
        
        This is where the magic happens:
        1. Retrieve buffered datasets
        2. Call optimization/re-training logic
        3. Emit new model to Kafka
        
        Args:
            instruction: Instruction from Observer
            ctx: Flink context
        """
        logger.info(f"Received instruction: {instruction.instruction_type.value} for model {instruction.model_id}")
        
        # Store instruction
        self.last_instruction_state.update(instruction)
        
        # Retrieve buffered datasets
        datasets = list(self.dataset_buffer_state.get())
        
        if not datasets:
            logger.warning("Received instruction but no datasets in buffer. Skipping.")
            return
        
        logger.info(f"Processing {len(datasets)} datasets with instruction {instruction.instruction_type.value}")
        
        # === CORE LOGIC PLACEHOLDER ===
        # This is where we'll call the RTCEF Python services
        # For now, we just simulate the behavior
        
        if instruction.instruction_type == InstructionType.OPTIMIZE:
            model = self._run_optimization(datasets, instruction)
        elif instruction.instruction_type == InstructionType.RETRAIN:
            model = self._run_retraining(datasets, instruction)
        else:
            logger.warning(f"Unknown instruction type: {instruction.instruction_type}")
            return
        
        # Emit the new model (will be connected to Kafka sink in main job)
        if model:
            ctx.output(model)
            logger.info(f"Emitted new model for {instruction.model_id}")
        
        # Clear dataset buffer after processing
        self.dataset_buffer_state.clear()
        logger.info("Dataset buffer cleared after processing")
    
    def _run_optimization(self, datasets: list, instruction: Instruction) -> Optional[str]:
        """
        Run optimization logic
        
        PLACEHOLDER: Will be replaced with actual RTCEF service calls
        
        Args:
            datasets: List of training datasets
            instruction: Optimization instruction
            
        Returns:
            Serialized model (placeholder)
        """
        logger.info("=== RUNNING OPTIMIZATION (MOCK) ===")
        logger.info(f"  Datasets: {len(datasets)}")
        logger.info(f"  Parameters: {instruction.parameters}")
        logger.info(f"  Metrics: {instruction.metrics}")
        
        # TODO: Call actual ModelFactory and Controller services here
        # For now, return a mock model
        mock_model = f"OPTIMIZED_MODEL_{instruction.model_id}_{instruction.timestamp}"
        
        logger.info(f"  Result: {mock_model}")
        return mock_model
    
    def _run_retraining(self, datasets: list, instruction: Instruction) -> Optional[str]:
        """
        Run re-training logic
        
        PLACEHOLDER: Will be replaced with actual RTCEF service calls
        
        Args:
            datasets: List of training datasets
            instruction: Re-training instruction
            
        Returns:
            Serialized model (placeholder)
        """
        logger.info("=== RUNNING RE-TRAINING (MOCK) ===")
        logger.info(f"  Datasets: {len(datasets)}")
        logger.info(f"  Parameters: {instruction.parameters}")
        
        # TODO: Implement the actual training loop here
        # This is where we'll call:
        # - ModelFactory to create candidate models
        # - Controller to evaluate them in a loop
        # - Return the best model
        
        # For now, return a mock model
        mock_model = f"RETRAINED_MODEL_{instruction.model_id}_{instruction.timestamp}"
        
        logger.info(f"  Result: {mock_model}")
        return mock_model
