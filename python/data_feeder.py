"""
Async CSV to Kafka Data Feeder.

This script reads a CSV file line by line and sends each line as a message
to a specified Kafka topic. It is designed for simulating real-time data
streams from static datasets.
"""

import sys
import time
import argparse
from kafka import KafkaProducer
from kafka.errors import KafkaError

def main():
    """
    Main entry point for the data feeder.
    Parses arguments, establishes Kafka connection, and starts the data loop.
    """
    parser = argparse.ArgumentParser(description='Async CSV to Kafka Data Feeder')
    parser.add_argument('--file', required=True, help='Path to CSV input file')
    parser.add_argument('--topic', default='input', help='Target Kafka topic')
    parser.add_argument('--bootstrap', default='kafka:29092', help='Kafka Bootstrap Servers')
    parser.add_argument('--delay', type=float, default=0.1, help='Delay between records in seconds')
    args = parser.parse_args()

    # Wait for Kafka to be ready
    producer = None
    for i in range(10):
        try:
            print(f"Connecting to Kafka at {args.bootstrap} (Attempt {i+1})...")
            producer = KafkaProducer(
                bootstrap_servers=args.bootstrap,
                value_serializer=str.encode
            )
            print("Connected to Kafka!")
            break
        except Exception as e:
            print(f"Waiting for Kafka... ({e})")
            time.sleep(2)
    
    if not producer:
        print("Failed to connect to Kafka. Exiting.")
        sys.exit(1)

    print(f"Starting infinite feed from {args.file} to topic '{args.topic}' with {args.delay}s delay...")
    
    count = 0
    while True:
        try:
            with open(args.file, 'r') as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    
                    # Blocking send for simplicity in this script
                    producer.send(args.topic, value=line)
                    count += 1
                    
                    if count % 100 == 0:
                        print(f"Sent {count} records...", flush=True)
                    
                    time.sleep(args.delay)
            
            print("Reached end of file. Restarting loop...", flush=True)
            
        except Exception as e:
            print(f"Error reading file or sending to Kafka: {e}")
            time.sleep(1)

if __name__ == "__main__":
    main()
