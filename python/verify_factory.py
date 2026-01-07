import json
import time
from kafka import KafkaProducer, KafkaConsumer
import uuid

def main():
    # Kafka Configuration
    producer = KafkaProducer(
        bootstrap_servers=['kafka:29092'],
        value_serializer=lambda v: json.dumps(v).encode('utf-8'),
        api_version=(2, 0, 0)
    )
    consumer = KafkaConsumer(
        'factory_reports',
        bootstrap_servers=['kafka:29092'],
        auto_offset_reset='latest',
        value_deserializer=lambda v: json.loads(v.decode('utf-8')),
        api_version=(2, 0, 0)
    )

    # Test Command
    cmd_id = str(uuid.uuid4())
    command = {
        "id": cmd_id,
        "type": "TRAIN",
        "dataset_path": "/opt/flink/data/test_dataset.csv", # Ensure this exists or use a dummy path handled by Mock Wayeb
        "pMin": 0.05,
        "gamma": 0.1
    }

    print(f"Sending command: {command}")
    producer.send('factory_commands', value=command)
    producer.flush()

    print("Waiting for report...")
    for msg in consumer:
        print(f"Received report: {msg.value}")
        if msg.value.get("reply_id") == cmd_id:
            print("SUCCESS: Received expected report for command.")
            break

if __name__ == "__main__":
    main()
