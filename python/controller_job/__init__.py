"""
PyFlink Controller Job - Flink-Native State Machine

Orchestrates model optimization using a state machine pattern:
- Receives OPTIMIZE/RETRAIN instructions from Observer (Kafka topic: instructions)
- Sends commands to ModelFactory (Kafka topic: factory_commands)
- Sends pause/play to WayebEngine (Kafka topic: enginesync)
- Receives training reports (Kafka topic: factory_reports)
"""

__version__ = "0.2.0"
