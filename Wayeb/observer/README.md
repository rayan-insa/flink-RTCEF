# RTCEF Observer Service

A Scala implementation of the Observer Service for the RTCEF (Run-Time Adaptation for Complex Event Forecasting) framework. This service monitors MCC (Matthews Correlation Coefficient) scores and intelligently decides between retraining or hyperparameter optimization to adapt the CEF model.

## Overview

The Observer Service implements **Algorithm 1** from the RTCEF paper. It continuously consumes scores from a Kafka topic and applies trend analysis to determine whether the model should be:

- **Retrained**: Generate a new PST model without changing hyperparameters
- **Optimized**: Generate a new PST model with hyperparameter optimization

## Architecture

```
Reports Topic (Kafka)
        │
        ▼
┌──────────────────────┐
│ KafkaReportsConsumer │  <- Consumes MCC scores
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  ScoringWindow       │  <- Maintains sliding window (size k)
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│  TrendAnalyzer       │  <- Linear regression on scores
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│ ObserverService      │  <- Algorithm 1 logic
│ (Decision making)    │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│KafkaInstructionSender│  <- Sends instructions
└──────────┬───────────┘
           │
           ▼
Instructions Topic (Kafka) -> Factory Service
```

## Algorithm 1: Observer Service

```
Require: k, guard_n, max_slope, min_score

1:  scores ← []
2:  guard ← −1
3:  while True do
4:    score_i ← consume(Reports)
5:    scores.update(score_i, k)
6:    pit_cond ← score_i < min_score
7:    slope_cond ← False
8:    if guard ≥ 0 then guard ← guard − 1
9:    if len(|scores|) > 2 then
10:     (a_i, b_i) ← fit_trend(scores)
11:     slope_cond ← a_i < max_slope
12:   if (slope_cond and guard ≥ 0) or pit_cond then
13:     send("instructions", "optimize")
14:     guard ← guard_n
15:   else if slope_cond then
16:     send("instructions", "retrain")
17:     guard ← guard_n
```

### Parameters

- **k**: Size of sliding window for score tracking (default: 10)
- **guard_n**: Guard period - number of polling cycles to wait before next instruction (default: 5)
- **max_slope**: Maximum acceptable slope for trend analysis (default: -0.01)
- **min_score**: Minimum acceptable MCC score threshold (default: 0.5)

### Decision Logic

The service makes decisions based on two conditions:

1. **Pit Condition** (`pit_cond`): A single score falls below `min_score`
   - Triggers OPTIMIZE instruction immediately

2. **Slope Condition** (`slope_cond`): Linear regression slope is worse than `max_slope`
   - If `guard ≥ 0`: Triggers OPTIMIZE instruction
   - If `guard < 0`: Triggers RETRAIN instruction
   - Sets guard to `guard_n` to prevent rapid consecutive instructions

## Module Structure

```
observer/
├── src/main/scala/observer/
│   ├── Main.scala                           # Entry point
│   ├── model/
│   │   ├── ObserverConfig.scala            # Configuration data class
│   │   └── Instruction.scala                # Instruction types
│   ├── service/
│   │   ├── KafkaReportsConsumer.scala       # Kafka consumer for scores
│   │   └── KafkaInstructionSender.scala     # Kafka producer for instructions
│   ├── state/
│   │   └── ScoringWindow.scala              # Sliding window state
│   ├── analytics/
│   │   └── TrendAnalyzer.scala              # Linear regression analysis
│   └── core/
│       └── ObserverService.scala            # Main algorithm implementation
├── src/test/scala/observer/
│   ├── model/
│   │   └── InstructionSpec.scala
│   ├── state/
│   │   └── ScoringWindowSpec.scala
│   └── analytics/
│       └── TrendAnalyzerSpec.scala
└── src/main/resources/
    └── observer.properties                  # Default configuration
```

## Configuration

### Configuration File (observer.properties)

```properties
# Kafka Configuration
bootstrap.servers=localhost:9092
group.id=observer-group

# Topic Configuration
reportsTopic=reports
instructionsTopic=instructions

# Observer Algorithm Parameters
k=10                    # Sliding window size
guardN=5               # Guard period
maxSlope=-0.01         # Maximum slope threshold
minScore=0.5           # Minimum score threshold

# Polling Configuration
pollTimeoutMs=1000     # Kafka poll timeout
```

### Programmatic Configuration

```scala
import observer.model.ObserverConfig

val config = ObserverConfig(
  kafkaBootstrapServers = "localhost:9092",
  reportsTopicName = "reports",
  instructionsTopicName = "instructions",
  groupId = "observer-group",
  k = 10,
  guardN = 5,
  maxSlope = -0.01,
  minScore = 0.5,
  pollTimeoutMs = 1000L
)
```

## Usage

### Build

From the Wayeb directory:

```bash
# Build all modules including observer
sbt rebuild

# Or just build observer
sbt observer/compile
sbt observer/test
sbt observer/assembly
```

### Run with Default Configuration

```bash
java -jar observer/target/observer-0.6.0-SNAPSHOT.jar
```

### Run with Custom Configuration

```bash
java -jar observer/target/observer-0.6.0-SNAPSHOT.jar /path/to/observer.properties
```

### Run Tests

```bash
sbt observer/test
```

## Kafka Topic Format

### Reports Topic (Input)

The Observer service expects to consume simple numeric MCC scores from the Reports topic:

```
Topic: reports
Message Format: <double_value>
Example: 0.75
```

### Instructions Topic (Output)

The Observer service produces instruction messages in JSON format:

```
Topic: instructions
Message Format: JSON
Example: {
  "type": "retrain",
  "timestamp": 1672531200000,
  "triggeredBy": "slope_condition"
}
```

**Instruction Types:**
- `"retrain"`: Generate new model without changing hyperparameters
- `"optimize"`: Generate new model with hyperparameter optimization

## Core Components

### ObserverService

The main service class implementing Algorithm 1. Orchestrates all other components.

```scala
val observer = new ObserverService(config)
observer.run()  // Blocking call
```

### KafkaReportsConsumer

Handles consuming MCC scores from Kafka.

```scala
val consumer = new KafkaReportsConsumer(config)
val scoreOption = consumer.pollScore()  // Returns Option[Double]
consumer.close()
```

### KafkaInstructionSender

Sends instructions to Kafka in JSON format.

```scala
val sender = new KafkaInstructionSender(config)
val instruction = Instruction.optimize("slope_condition")
sender.sendInstruction(instruction)
sender.close()
```

### ScoringWindow

Maintains a sliding window of recent scores with configurable size.

```scala
val window = new ScoringWindow(k = 10)
window.update(0.75)
window.getScores    // Returns List[Double]
window.hasEnoughScores(3)  // Returns Boolean
```

### TrendAnalyzer

Performs linear regression to fit trends in score data.

```scala
val (slope, intercept) = TrendAnalyzer.fitTrend(scores)
val rSquared = TrendAnalyzer.calculateRSquared(scores, slope, intercept)
```

## Parameter Tuning Guide

### Choosing k (Window Size)

- **Smaller values (3-5)**: Respond quickly to trends but more noise sensitivity
- **Recommended (10-20)**: Good balance between responsiveness and stability
- **Larger values (20+)**: Smoother trends but slower to react to changes

### Choosing max_slope

- **-0.05**: Very sensitive, triggers on any slight decline
- **-0.01**: Recommended, triggers on noticeable degradation
- **-0.005**: Conservative, only triggers on significant decline

### Choosing min_score

- Set based on your domain's acceptable performance threshold
- Lower threshold = fewer optimizer triggers but more sensitive to pit conditions
- Higher threshold = more frequent optimization

### Choosing guard_n

- **Smaller (2-3)**: More frequent instructions possible
- **Recommended (5-10)**: Prevents instruction flooding while allowing adaptation
- **Larger (15+)**: More conservative, prevents rapid model changes

## Integration with RTCEF Framework

The Observer Service is designed to work with the complete RTCEF pipeline:

1. **Engine (Wayeb)**: Produces MCC scores → Reports topic
2. **Observer (this service)**: Consumes scores → Makes decisions
3. **Factory**: Consumes instructions → Generates new models
4. **Collector**: Gathers data for model training/testing

## Logging

The service uses SLF4J with Logback. Configure logging in `logback.xml`:

```xml
<logger name="observer" level="DEBUG"/>
```

Log levels:
- **ERROR**: Critical failures
- **WARN**: Non-critical issues
- **INFO**: Major events (instruction sent, configuration loaded)
- **DEBUG**: Detailed processing (scores received, conditions evaluated)
- **TRACE**: Polling details

## Testing

Unit tests are provided for core components:

```bash
sbt observer/test
```

Test coverage includes:
- Trend analysis calculations
- Scoring window management
- Instruction serialization
- Configuration loading

## Troubleshooting

### Service fails to connect to Kafka

- Verify Kafka is running: `bin/kafka-broker.sh` should show logs
- Check `bootstrap.servers` configuration
- Verify topic names exist: `bin/kafka-topics.sh --list`

### No instructions are sent

- Check if scores are being received: Enable DEBUG logging
- Verify `maxSlope` threshold isn't too conservative
- Check `min_score` threshold

### Too many instructions sent

- Increase `guardN` to enforce longer wait between instructions
- Adjust `maxSlope` to be less sensitive
- Increase window size `k` for smoother trend analysis

## Performance Characteristics

- **Memory**: ~1MB (mostly scoring window buffer)
- **CPU**: Minimal (linear regression on k scores per poll)
- **Latency**: ~200ms (Kafka poll + trend analysis)
- **Throughput**: Processes incoming scores as they arrive

## Future Enhancements

- [ ] Multiple trend analysis algorithms (EWMA, polynomial, etc.)
- [ ] Adaptive parameter tuning based on performance
- [ ] Metrics collection (instructions sent, guard periods, etc.)
- [ ] Health checks and monitoring endpoints
- [ ] Graceful failover for Kafka connectivity issues
- [ ] Advanced statistical analysis (ARIMA, Kalman filter)

## License

This project is part of the RTCEF framework. See the main LICENSE file for details.

## References

- RTCEF Paper: "Run-Time Adaptation of Complex Event Forecasting"
- Wayeb: Complex Event Forecasting Engine
- Apache Kafka: Distributed Event Streaming Platform
