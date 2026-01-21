# RTCEF Implementation: Differences & Rationale

This document outlines the architectural and implementation differences between the original Python/Scala RTCEF framework (as described in the research papers) and this high-performance Apache Flink implementation.

---

## 1. Model Synchronization Mechanism

**Original**: "Stop-and-Go" via a synchronization topic (`pause` / `play`).
**Flink**: Asynchronous "Transition Period" based on Event Time.

* **Description**: In the original framework, inference is halted while a new model is loaded. In this Flink implementation, the `WayebEngine` continues processing using the old model until the stream reaches a specific `syncTimestamp` (calculated as `currentTime + productionTime`), at which point it swaps the model mid-stream.
* **Justification**: Flink is designed for continuous, low-latency streaming. Blocking a job to wait for an external event (like model training) violates the streaming paradigm and complicates offset management.
* **Pros**: Zero downtime for inference; eliminates the need for a blocking synchronization topic.
* **Cons**: Introduit a "grace period" where an outdated model is used until the new one is activated.

## 2. Data Collection Strategy (Collector)

**Original**: "Data-Driven" Bucketing.
**Flink**: "Wall-Clock" / Fixed-Window Bucketing.

* **Description**:
  * *Original*: A bucket starts only when the first event arrives (e.g., 10:12) and ends exactly `bucket_size` later (11:12). If there is a data gap, no empty buckets are created.
  * *Flink*: Time is divided into fixed slices aligned with the Unix Epoch (e.g., 10:00-11:00, 11:00-12:00).
* **Justification**: Flink’s windowing API is naturally aligned with the epoch. This provides high predictability and leverages Flink's optimized watermark-based triggering.
* **Pros**: Predictable execution; better alignment across multiple parallel sub-tasks.
* **Cons**: Potential for "fragmented" first buckets if data starts mid-window; may generate empty files/notifications during data silences (unless configured otherwise).

## 3. Data Serialization & Transport

**Original**: Avro + Confluent Schema Registry.
**Flink**: JSON (Control) + CSV (Data).

* **Description**: The original stack uses Avro for strictly typed, binary-efficient message passing. This implementation uses human-readable JSON for Kafka messages and plain CSV for datasets on disk.
* **Justification**: Simplifies the technical stack by removing the dependency on a Schema Registry. Facilitates rapid prototyping and cross-language debugging (Java/Scala/Python).
* **Pros**: Easy to debug; lower infrastructure overhead; immediate interoperability.
* **Cons**: Larger message size; lack of strict schema enforcement at the transport layer.

## 4. State Management & Fault Tolerance

**Original**: Often managed in-memory or via external databases (e.g., Redis).
**Flink**: Native Flink Managed State (`ValueState`, `BroadcastState`).

* **Description**: Flink manages the internal state of the Wayeb engines and predictors. This state is periodically backed up to a distributed filesystem (Checkpoints).
* **Justification**: Provides "Exactly-Once" processing guarantees and automatic recovery. If a worker fails, Flink restores the engine's state immediately without losing progress.
* **Pros**: High reliability; seamless scalability of state across a cluster.
* **Cons**: Complexity in serializing/deserializing complex Scala objects (Wayeb FSMs) into Flink's state backend.

## 5. Dataset Assembly Pipeline

**Original**: Discrete services for collecting and notifying.
**Flink**: Integrated Pipeline (Collector + Notifier in one job).

* **Description**: In Flink, the `InferenceJob` performs inference and data collection/notification simultaneously. The `ModelFactoryEngine` pre-assembles datasets (concatenating CSV files) as soon as it receives a notification.
* **Justification**: Reduces the number of moving parts and minimizes network hops between services.
* **Pros**: Simplified orchestration; lower latency from "data ready" to "training start".
* **Cons**: Tight coupling between units (though mitigated by Kafka's decoupling).

## 6. Special Event Handling (`ResetEvent`)

**Original**: Explicit handling to reset FSM states.
**Flink**: Currently ignored (Voluntary simplification).

* **Description**: The original system uses `ResetEvent` to clear partial matches when data streams are stitched together. This implementation currently treats all events as standard data points.
* **Justification**: Focus is placed on steady-state performance. For the current maritime use case, state "pollution" from disconnected segments is considered a secondary concern compared to implementation complexity.
* **Pros**: Leaner engine code.
* **Cons**: Potential for incorrect predictions if a ship's context changes significantly after a long data gap without a state reset.

---

## 7. Parallelism & Scalability

**Original**: Service-based (Python), often single-threaded or manually sharded.
**Flink**: Native Keyed-Parallelism.

* **Description**: Flink automatically partitions the stream by `mmsi`. Each ship's state (engine, buffers, metrics) is isolated and can be processed on different cluster nodes.
* **Pros**: Horizontal scalability is "free"; state is localized, reducing lock contention.
* **Cons**: Requires careful management of the `BroadcastState` to ensure all parallel instances receive model updates simultaneously.

## 8. Out-of-Order Handling (Watermarks)

**Original**: Implicit or manual handling of event ordering.
**Flink**: Native Watermark-based Event Time.

* **Description**: Flink uses Watermarks to handle late-arriving positions. This ensures that windows (for the Collector) and model swaps (for the Engine) occur based on the "logical" time of the data, not when the data arrives at the server.
* **Pros**: Robustness against network delays or out-of-order Kafka partitions.
* **Cons**: Correct Watermark generation is critical; a bad strategy can stall the entire pipeline.

---
*Note: This document reflects the current state of the architecture and is subject to updates as the framework evolves.*
