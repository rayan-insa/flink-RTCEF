"""
Mock Dataset Producer

Simulates the Collector sending training datasets to Kafka.
Useful for testing the Controller Job without the full pipeline.
"""

from kafka import KafkaProducer
import json
import time
import logging
import random
from typing import List, Dict, Any

from controller_job.models.dataset import TrainingDataset


logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class MockDatasetProducer:
    """
    Mock producer that simulates Collector sending datasets
    """
    
    def __init__(self, bootstrap_servers: str = 'localhost:9092', topic: str = 'datasets'):
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
        logger.info(f"MockDatasetProducer initialized for topic '{topic}'")
    
    def send_dataset(
        self,
        dataset_id: str,
        events: List[Dict[str, Any]],
        model_id: str = "maritime_model_v1",
        metadata: Dict[str, Any] = None
    ):
        """
        Send a mock dataset to Kafka
        
        Args:
            dataset_id: Unique identifier for this dataset
            events: List of events in the dataset
            model_id: Associated model ID
            metadata: Additional metadata
        """
        # Ensure metadata contains model_id for keying
        if metadata is None:
            metadata = {}
        metadata['model_id'] = model_id
        
        dataset = TrainingDataset(
            dataset_id=dataset_id,
            timestamp=int(time.time() * 1000),
            events=events,
            metadata=metadata
        )
        
        # Serialize to JSON
        dataset_dict = {
            'dataset_id': dataset.dataset_id,
            'timestamp': dataset.timestamp,
            'events': dataset.events,
            'metadata': dataset.metadata
        }
        
        # Send to Kafka
        future = self.producer.send(self.topic, dataset_dict)
        future.get(timeout=10)
        
        logger.info(f"Sent dataset {dataset_id} with {len(events)} events")
    
    def generate_mock_maritime_events(self, num_events: int = 100) -> List[Dict[str, Any]]:
        """
        Generate mock maritime events for testing
        
        Args:
            num_events: Number of events to generate
            
        Returns:
            List of mock events
        """
        events = []
        base_timestamp = int(time.time() * 1000)
        
        for i in range(num_events):
            event = {
                'mmsi': random.choice(['123456789', '987654321', '555555555']),
                'latitude': random.uniform(35.0, 45.0),
                'longitude': random.uniform(-10.0, 10.0),
                'speed': random.uniform(0.0, 25.0),
                'heading': random.uniform(0.0, 360.0),
                'timestamp': base_timestamp + (i * 1000),  # Events 1 second apart
                'event_type': random.choice(['position_update', 'speed_change', 'course_change'])
            }
            events.append(event)
        
        return events
    
    def send_mock_dataset(self, dataset_id: str = None, num_events: int = 100):
        """
        Generate and send a mock dataset
        
        Args:
            dataset_id: Dataset ID (auto-generated if None)
            num_events: Number of events to include
        """
        if dataset_id is None:
            dataset_id = f"dataset_{int(time.time())}"
        
        events = self.generate_mock_maritime_events(num_events)
        
        self.send_dataset(
            dataset_id=dataset_id,
            events=events,
            metadata={
                'source': 'mock_collector',
                'window_start': events[0]['timestamp'],
                'window_end': events[-1]['timestamp'],
                'event_count': len(events)
            }
        )
    
    def close(self):
        """Close the producer"""
        self.producer.close()
        logger.info("MockDatasetProducer closed")


def main():
    """
    Example usage: Send some test datasets
    """
    producer = MockDatasetProducer()
    
    try:
        logger.info("Sending test datasets...")
        
        # Wait a bit for Kafka to be ready
        time.sleep(2)
        
        # Send 3 mock datasets
        for i in range(3):
            producer.send_mock_dataset(
                dataset_id=f"test_dataset_{i}",
                num_events=50
            )
            time.sleep(0.5)
        
        logger.info("Test datasets sent successfully")
        
    except Exception as e:
        logger.error(f"Error sending datasets: {e}", exc_info=True)
    finally:
        producer.close()


if __name__ == "__main__":
    main()
