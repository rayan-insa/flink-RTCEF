#!/bin/bash

# RTCEF Observer Service - Simulation Script
# This script simulates the complete RTCEF pipeline with mock data

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$SCRIPT_DIR/.."

echo "======================================================================"
echo "RTCEF Observer Service - Simulation Setup"
echo "======================================================================"

# Check if Kafka is available
check_kafka() {
    echo -n "Checking Kafka availability..."
    if ! command -v kafka-topics.sh &> /dev/null; then
        echo "Kafka tools not found in PATH"
        echo "Please ensure Kafka is installed and KAFKA_HOME is in PATH"
        return 1
    fi
    echo "OK"
    return 0
}

# Create topics
create_topics() {
    echo "Creating Kafka topics..."
    
    kafka-topics.sh --create \
        --topic reports \
        --partitions 1 \
        --replication-factor 1 \
        --bootstrap-server localhost:9092 \
        --if-not-exists 2>/dev/null || true
    
    kafka-topics.sh --create \
        --topic instructions \
        --partitions 1 \
        --replication-factor 1 \
        --bootstrap-server localhost:9092 \
        --if-not-exists 2>/dev/null || true
    
    echo "Topics created"
}

# Build observer
build_observer() {
    echo "Building Observer service..."
    cd "$PROJECT_ROOT"
    sbt observer/assembly
    echo "Build completed"
}

# Show usage
show_usage() {
    cat << EOF
Usage: $0 [COMMAND]

Commands:
  setup       - Check Kafka and create topics
  build       - Build the observer service
  example     - Run component examples
  help        - Show this help message

Examples:
  $0 setup          # Verify Kafka and create topics
  $0 build          # Build observer
  $0 example        # Run component examples

To run the full Observer service:
  java -jar $PROJECT_ROOT/observer/target/observer-0.6.0-SNAPSHOT.jar

To run with custom config:
  java -jar $PROJECT_ROOT/observer/target/observer-0.6.0-SNAPSHOT.jar /path/to/observer.properties
EOF
}

# Main
case "${1:-help}" in
    setup)
        check_kafka && create_topics
        ;;
    build)
        build_observer
        ;;
    example)
        cd "$PROJECT_ROOT"
        sbt 'observer/runMain observer.examples.ObserverExample'
        ;;
    help)
        show_usage
        ;;
    *)
        echo "Unknown command: $1"
        show_usage
        exit 1
        ;;
esac
