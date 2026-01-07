# PyFlink Controller Job

The Controller Job orchestrates model optimization and re-training by synchronizing two Kafka streams:

1. **Instructions** from the Observer (metrics/commands)
2. **Datasets** from the Collector (training data)

## Architecture

```
┌─────────────┐     ┌──────────────┐
│  Observer   │────▶│ Instructions │ (Kafka)
└─────────────┘     └──────┬───────┘
                           │
                           ▼
                    ┌──────────────────┐
                    │ CoProcess        │
                    │ Function         │◀────┐
                    └────────┬─────────┘     │
                             │               │
                             ▼               │
                    ┌──────────────────┐     │
                    │ Model Updates    │     │
                    └──────────────────┘     │
                                             │
┌─────────────┐     ┌──────────────┐        │
│  Collector  │────▶│   Datasets   │────────┘
└─────────────┘     └──────────────┘ (Kafka)
```

## Project Structure

```
python/
├── controller_job/
│   ├── __init__.py
│   ├── main.py                    # Main job entry point
│   ├── functions/
│   │   └── controller_coprocess.py  # CoProcessFunction implementation
│   └── models/
│       ├── instruction.py         # Instruction data model
│       └── dataset.py            # Dataset data model
├── mocks/
│   ├── mock_instruction_producer.py  # Test producer for instructions
│   └── mock_dataset_producer.py     # Test producer for datasets
├── requirements.txt
└── docker-compose.kafka.yml      # Kafka infrastructure
```

## Setup

### 1. Install Dependencies

```bash
cd python
pip install -r requirements.txt
```

### 2. Start Kafka

```bash
docker-compose -f docker-compose.kafka.yml up -d
```

Verify Kafka is running:

```bash
docker ps | grep kafka
```

### 3. Test with Mock Data

In separate terminals:

**Terminal 1: Send mock datasets**

```bash
python -m mocks.mock_dataset_producer
```

**Terminal 2: Send mock instructions**

```bash
python -m mocks.mock_instruction_producer
```

**Terminal 3: Run the Controller Job**

```bash
python -m controller_job.main
```

## How It Works

### CoProcessFunction

The `ControllerCoProcessFunction` synchronizes two streams:

- **Stream 1 (processElement1)**: Datasets arrive and are buffered in Flink State
- **Stream 2 (processElement2)**: Instructions arrive, trigger processing with buffered datasets

### State Management

- `dataset_buffer_state`: Accumulates datasets until an instruction arrives
- `last_instruction_state`: Stores the last instruction for reference

### Processing Flow

1. Datasets continuously arrive and are stored in the buffer
2. When an Instruction arrives:
   - Retrieve all buffered datasets
   - Call optimization/re-training logic (placeholder for now)
   - Emit updated model to Kafka
   - Clear dataset buffer

## Next Steps

### Integration with RTCEF Logic

The current implementation has placeholder methods:

- `_run_optimization()`
- `_run_retraining()`

These will be replaced with calls to the original RTCEF Python services:

- `ModelFactory`: Creates candidate models
- `Controller`: Evaluates models in a loop until convergence

### Topics to Configure

Current Kafka topics:

- `instructions`: Instructions from Observer
- `datasets`: Training datasets from Collector  
- `model-updates`: Model updates to Wayeb Engine

## Development Notes

- Currently uses single parallelism for simplicity
- State is keyed by `model_id` for proper partitioning
- Models are currently serialized as strings (placeholder)
- No watermarks/event time processing yet (can be added later)

## Testing

Run unit tests (TODO):

```bash
pytest tests/
```

## Deployment

For production deployment to Flink cluster:

```bash
flink run -py controller_job/main.py
```
