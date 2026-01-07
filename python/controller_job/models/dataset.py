"""
Dataset data model

Represents training datasets collected by the Collector.
"""

from dataclasses import dataclass
from typing import List, Dict, Any


@dataclass
class TrainingDataset:
    """
    Training dataset from Collector
    
    Attributes:
        dataset_id: Unique identifier for this dataset
        timestamp: Unix timestamp of dataset creation
        events: List of events in the dataset
        metadata: Additional metadata (window info, statistics, etc.)
    """
    dataset_id: str
    timestamp: int
    events: List[Dict[str, Any]]
    metadata: Dict[str, Any]
    
    @classmethod
    def from_json(cls, json_str: str) -> 'TrainingDataset':
        """Parse TrainingDataset from JSON string"""
        import json
        data = json.loads(json_str)
        return cls(
            dataset_id=data['dataset_id'],
            timestamp=data['timestamp'],
            events=data['events'],
            metadata=data.get('metadata', {})
        )
    
    def to_json(self) -> str:
        """Serialize to JSON string"""
        import json
        return json.dumps({
            'dataset_id': self.dataset_id,
            'timestamp': self.timestamp,
            'events': self.events,
            'metadata': self.metadata
        })
    
    def size(self) -> int:
        """Return number of events in dataset"""
        return len(self.events)
