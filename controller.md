# Controller & ModelFactory Architecture

## Overview

The architecture splits the "Learning" capability of RTCEF into two distinct Flink Jobs: a lightweight Python **Controller** for logic/optimization and a heavy Java **ModelFactory** for training execution. This separation ensures stability and allows independent scaling.

## Infrastructure Stack

* **Processing Engine**: Apache Flink (1.16+) running in Docker containers.
* **Messaging Bus**: Apache Kafka (managed by Zookeeper).
* **Storage**: Shared Docker Volume (mounted at `/opt/flink/data`) for storing Datasets (`.csv`) and Models (`.spst`).

## Component 1: Python Controller Job ("The Brain")

Responsible for the decision loop. It processes external instructions and feedback from the factory to issue training commands.

* **Type**: PyFlink Job.
* **Key Code**:
  * `main.py`: Entry point. Sets up Kafka sources/sinks.
  * `controller_coprocess.py`: `KeyedCoProcessFunction`. Merges `instructions` stream and `factory_reports` stream.
  * `optimizer.py`: Wraps `scikit-optimize` logic. Maintains optimization history (currently transient in memory).
* **IO Data**:
  * **In**: `instructions` (Topic: `instructions` | Key: `model_id`)
  * **In**: `factory_reports` (Topic: `factory_reports` | via CoProcess)
  * **Out**: `factory_commands` (Topic: `factory_commands`)

## Component 2: Java Model Factory Job ("The Worker")

Responsible for the heavy computational tasks (training Wayeb models).

* **Type**: Java Flink Job (with Scala interop for Wayeb).
* **Key Code**:
  * `ModelFactoryJob.java`: Entry point. Connects Commands and Datasets streams.
  * `ModelFactoryEngine.java`: `CoProcessFunction`.
    * **Stream 1 (Commands)**: Triggers training using parameters from the command + current dataset in state.
    * **Stream 2 (Datasets)**: Receives metadata (path, id) and updates the `currentDataset` ValueState.
  * `WayebBridge.scala`: Bridge invoking the internal Wayeb library for file-based training.
* **IO Data**:
  * **In**: `factory_commands` (Topic: `factory_commands`)
  * **In**: `datasets` (Topic: `datasets` | Contains paths to files, not raw data)
  * **Out**: `factory_reports` (Topic: `factory_reports`)
  * **Side Effect**: Writes `.spst` model files to the Shared Volume.

## Synchronization & Data Flow

The system implements a **Stateful Command Pattern**:

1. **Dataset Availability**: The Collector (external job) writes a file and publishes its path to the `datasets` topic.
2. **State Update**: `ModelFactoryEngine` consumes the path and updates its local Flink State (`currentDataset`).
3. **Instruction**: The Observer sends an `OPTIMIZE` instruction to the Controller.
4. **Command**: The Controller generates hyperparameters and sends a `TRAIN` command.
5. **Execution**: The Factory receives `TRAIN`, reads the **latest dataset path** from its State, and triggers `WayebBridge.train()`.
6. **Feedback**: Factory emits a `SUCCESS` report with metrics. Controller calls `optimizer.update_model()` to refine future parameters.
