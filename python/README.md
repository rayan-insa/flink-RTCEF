# PyFlink Controller Job

The Controller Job is the **brain** of the RTCEF system. It orchestrates model optimization and re-training using a Flink-native state machine architecture.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           KAFKA TOPICS                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌───────────┐         ┌───────────────────┐         ┌───────────────┐ │
│  │instructions│────────▶│ Controller        │────────▶│factory_commands│
│  └───────────┘         │ CoProcessFunction │         └───────────────┘ │
│        ▲               │ (ValueState)      │               │           │
│        │               └─────────┬─────────┘               ▼           │
│        │                         │                  ┌───────────────┐  │
│  ┌─────┴─────┐                   │                  │ ModelFactory  │  │
│  │ Observer  │                   │                  │ (Java)        │  │
│  │ (Java)    │                   ▼                  └───────┬───────┘  │
│  └───────────┘           ┌───────────────┐                  │          │
│                          │  enginesync   │                  ▼          │
│                          └───────┬───────┘          ┌───────────────┐  │
│                                  │                  │factory_reports│  │
│                                  ▼                  └───────┬───────┘  │
│                          ┌───────────────┐                  │          │
│                          │ WayebEngine   │◀─────────────────┘          │
│                          │ (Java)        │                             │
│                          └───────────────┘                             │
└─────────────────────────────────────────────────────────────────────────┘
```

## Kafka Topics

| Topic | Producer | Consumer | Description |
|-------|----------|----------|-------------|
| `instructions` | Java Observer | Python Controller | OPTIMIZE/RETRAIN commands |
| `factory_commands` | Python Controller | Java ModelFactory | train/opt_initialise/opt_step/opt_finalise |
| `factory_reports` | Java ModelFactory | Python Controller + WayebEngine | Model training results |
| `enginesync` | Python Controller | Java WayebEngine | pause/play commands |

## Project Structure

```
python/
├── controller_job/
│   ├── __init__.py
│   ├── main.py                      # PyFlink job entry point
│   ├── optimizer.py                 # Bayesian optimizer (stateless API)
│   ├── sync.py                      # pause/play command helpers
│   ├── functions/
│   │   ├── __init__.py
│   │   └── controller_coprocess.py  # State machine implementation
│   └── models/
│       ├── __init__.py
│       └── instruction.py           # Instruction dataclass
├── mocks/
│   └── mock_instruction_producer.py # Test producer
├── requirements.txt
└── Dockerfile
```

## Key Components

### 1. `main.py` - PyFlink Job Entry Point

Sets up the Flink DataStream graph:

- Creates Kafka sources for `instructions` and `factory_reports`
- Connects streams to `ControllerCoProcessFunction`
- Configures side output sinks for `factory_commands` and `enginesync`

### 2. `optimizer.py` - Bayesian Optimizer

Implements hyperparameter optimization using scikit-optimize (skopt).

**Two modes:**

- **Synchronous**: `run_optimization()` for blocking scripts
- **Event-Driven**: Step-by-step API for Flink state machine:
  - `init_session()` - Start new optimization
  - `ask()` - Get next params (pMin, gamma)
  - `tell(f_val)` - Update Bayesian model with result
  - `finalize()` - Select best model
  - `get_state_snapshot()` / `load_state_snapshot()` - Serialize/restore state

### 3. `controller_coprocess.py` - State Machine

A `KeyedCoProcessFunction` that manages optimization sessions:

**States:**

- `IDLE` → `INITIALIZING` → `WAITING_FOR_REPORT` → `OPTIMIZING` → `FINALIZING` → `IDLE`

**State Persistence:**

- Uses Flink `ValueState` to store `OptimizationSession`
- Optimizer's Bayesian model is pickled and base64-encoded

### 4. `sync.py` - Engine Synchronization

Helper functions to create pause/play commands for WayebEngine:

- `create_pause_command(timestamp)` - Pause inference during optimization
- `create_play_command(timestamp, model_id)` - Resume with new model

### 5. `instruction.py` - Data Model

Dataclass representing commands from Observer:

- `OPTIMIZE` - Run full hyperparameter optimization
- `RETRAIN` - Simple re-training with fixed params

## Setup

### Dependencies

```bash
pip install -r requirements.txt
```

Key packages:

- `apache-flink==1.17.2` - Flink Python API
- `scikit-optimize>=0.9.0` - Bayesian optimization
- `confluent-kafka>=2.3.0` - Kafka client for mocks

### Running

With Flink cluster:

```bash
flink run -py controller_job/main.py
```

Or via Docker:

```bash
make start
make submit
```

## Testing

Send a mock instruction:

```bash
python -m mocks.mock_instruction_producer
```

## Configuration

Environment variables:

- `KAFKA_BOOTSTRAP_SERVERS` (default: `kafka:29092`)
- `INSTRUCTION_TOPIC` (default: `instructions`)
- `REPORT_TOPIC` (default: `factory_reports`)
- `COMMAND_TOPIC` (default: `factory_commands`)
- `SYNC_TOPIC` (default: `enginesync`)
