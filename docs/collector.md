# Collector: Dataset Collection & Notification

## Overview

The Collector is responsible for two critical tasks in the RTCEF pipeline:

1. **Persist Events**: Write incoming events to disk in time-bucketed folders.
2. **Notify**: Emit dataset notifications to Kafka so the ModelFactory knows training data is available.

## Architecture

```
                         ┌──────────────────────────┐
                         │       mainStream         │
                         │     (GenericEvent)       │
                         └───────────┬──────────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              │                      │                      │
              ▼                      ▼                      ▼
    ┌─────────────────┐   ┌─────────────────────┐   ┌─────────────────┐
    │    FileSink     │   │ DatasetNotification │   │   WayebEngine   │
    │  (Disk Write)   │   │    Process          │   │  (Detection)    │
    └────────┬────────┘   └──────────┬──────────┘   └─────────────────┘
             │                       │
             ▼                       ▼
    ┌─────────────────┐   ┌─────────────────────┐
    │  /data/saved_   │   │   Kafka Topic:      │
    │  datasets/...   │   │   "datasets"        │
    └─────────────────┘   └─────────────────────┘
```

## Components

### 1. FileSink (Branch A)

Writes events to disk using Flink's `FileSink` connector.

| Parameter | Default | Description |
|:----------|:--------|:------------|
| `outputPath` | `/opt/flink/data/saved_datasets` | Base directory for buckets |
| `bucketSize` | 86400 (1 day) | Window size in seconds |
| `naming` | `dataset_` | Prefix for bucket folder names |

**Output Structure:**

```
/opt/flink/data/saved_datasets/
├── dataset_1443744000/
│   ├── part-xxxxx-0
│   └── part-xxxxx-1
├── dataset_1443830400/
│   └── part-xxxxx-0
└── ...
```

### 2. DatasetNotificationProcess (Branch B)

Emits JSON notifications to Kafka when a time window closes.

**Logic:**

1. At window close, compute bucket ID from `window.getEnd() / 1000`.
2. Add bucket ID to a rolling buffer (max size = `lastK`).
3. Construct JSON message with `buckets_range` array.
4. Emit to Kafka.

**JSON Format:**

```json
{
  "dataset_id": "ds-42",
  "path_prefix": "/opt/flink/data/saved_datasets/dataset_",
  "buckets_range": [1443744000, 1443830400, 1444089600],
  "version": 42,
  "timestamp": 1444089600,
  "bucket_count": 3
}
```

| Parameter | Default | Description |
|:----------|:--------|:------------|
| `lastK` | 3 | Number of buckets to include in each dataset |
| `kafka-servers` | `kafka:29092` | Kafka bootstrap servers |
| `datasets-topic` | `datasets` | Kafka topic for notifications |

## Differences from Original Python Implementation

| Feature | Original (`collector.py`) | Flink Implementation | Justification |
|:--------|:--------------------------|:---------------------|:--------------|
| **Bucketing** | Data-driven (first event starts bucket) | Wall-clock aligned (fixed time slots) | Standard Flink pattern, more predictable in distributed systems |
| **Collection Method** | Supports `lastK` and `scoreBased` | Only `lastK` | Simplified scope; `scoreBased` requires feedback loop from Wayeb |
| **Bucket IDs** | Sequential integers (0, 1, 2...) | Epoch timestamps (1443744000...) | Aligns with Flink's time-based windowing |
| **Notification Trigger** | On bucket close | On window close (watermark-driven) | Native Flink event-time semantics |
| **Cleanup** | Explicit file deletion after K buckets | Relies on external cleanup (optional) | Flink FileSink doesn't support deletion |

## Consumer: ModelFactoryEngine

The `ModelFactoryEngine` consumes notifications from Kafka and:

1. Parses `path_prefix` and `buckets_range`.
2. On `TRAIN` command, concatenates all bucket files into a single temp CSV.
3. Passes the temp file to `WayebBridge.train()`.

**File Concatenation:**

```java
for (Long bucketId : bucketsRange) {
    String bucketDir = pathPrefix + bucketId;
    Files.list(Paths.get(bucketDir))
         .filter(p -> p.startsWith("part-"))
         .forEach(p -> appendToTempFile(p));
}
```

## Usage

```bash
# Run with default parameters
make submit

# Run with custom bucket size (60 seconds for testing)
make submit BUCKET_SIZE=60

# Monitor notifications
docker exec flink-rtcef-kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 --topic datasets --from-beginning
```

## Testing

### Verification Checklist

- [x] FileSink writes to `/opt/flink/data/saved_datasets/dataset_<timestamp>/`
- [x] DatasetNotificationProcess emits JSON to Kafka
- [x] JSON contains `path_prefix` and `buckets_range` array
- [x] First notification has `bucket_count: 1`, subsequent ones grow to K
- [x] ModelFactoryEngine correctly parses new format
- [x] Backward compatibility with legacy `uri` format maintained
