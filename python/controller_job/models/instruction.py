"""
Instruction data model

Represents commands from the Observer to trigger optimization or re-training.
"""

from dataclasses import dataclass
from enum import Enum
from typing import Dict, Any


class InstructionType(Enum):
    """Type of instruction from Observer"""
    OPTIMIZE = "optimize"
    RETRAIN = "retrain"
    UPDATE_PARAMS = "update_params"


@dataclass
class Instruction:
    """
    Instruction from Observer to Controller
    
    Attributes:
        instruction_type: Type of instruction (optimize/retrain)
        timestamp: Unix timestamp of instruction
        model_id: ID of the model to act upon
        parameters: Additional parameters for the instruction
        metrics: Performance metrics that triggered this instruction
    """
    instruction_type: InstructionType
    timestamp: int
    model_id: str
    parameters: Dict[str, Any]
    metrics: Dict[str, float]
    
    @classmethod
    def from_json(cls, json_str: str) -> 'Instruction':
        """Parse Instruction from JSON string"""
        import json
        data = json.loads(json_str)
        return cls(
            instruction_type=InstructionType(data['instruction_type']),
            timestamp=data['timestamp'],
            model_id=data['model_id'],
            parameters=data.get('parameters', {}),
            metrics=data.get('metrics', {})
        )
    
    def to_json(self) -> str:
        """Serialize to JSON string"""
        import json
        return json.dumps({
            'instruction_type': self.instruction_type.value,
            'timestamp': self.timestamp,
            'model_id': self.model_id,
            'parameters': self.parameters,
            'metrics': self.metrics
        })
