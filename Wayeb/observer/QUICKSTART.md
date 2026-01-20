# Quick Start Guide - Observer Service

## 30-Second Setup

```bash
# 1. Navigate to Wayeb directory
cd Wayeb

# 2. Build observer module
sbt observer/assembly

# 3. Run with default configuration
java -jar observer/target/observer-0.6.0-SNAPSHOT.jar

# Output should show:
# [INFO] Starting RTCEF Observer Service
# [INFO] Started polling for scores
```

## Configuration

**Default Configuration File**: `observer/src/main/resources/observer.properties`

**Key Parameters**:
```properties
# Kafka
bootstrap.servers=localhost:9092

# Topics
reportsTopic=reports           # Where scores come from
instructionsTopic=instructions # Where instructions go

# Algorithm
k=10                           # Sliding window size
guardN=5                       # Guard period
maxSlope=-0.01                 # Slope threshold
minScore=0.5                   # Score threshold
```

**Custom Configuration**:
```bash
java -jar observer/target/observer-0.6.0-SNAPSHOT.jar /path/to/my-config.properties
```

## What It Does

### Input: MCC Scores
Consumes from `reports` Kafka topic:
```
0.75
0.74
0.73
...
```

### Output: Instructions
Produces to `instructions` Kafka topic:
```json
{"type":"retrain","timestamp":1234567890,"triggeredBy":"slope_condition"}
{"type":"optimize","timestamp":1234567891,"triggeredBy":"pit_condition"}
```

## Decision Rules

**OPTIMIZE if**:
- Single score < `minScore` (pit condition), OR
- Trend declining (slope < `maxSlope`) AND guard is active

**RETRAIN if**:
- Trend declining (slope < `maxSlope`) AND guard is inactive

**Wait if**:
- In guard period (wait `guardN` polls)
- Not enough data for trend analysis (< 3 scores)

## Testing

### Run Unit Tests
```bash
sbt observer/test
```

### Run Component Examples
```bash
sbt 'observer/runMain observer.examples.ObserverExample'
```

### Monitor Kafka Topics (Terminal 1)
```bash
kafka-console-consumer.sh --topic reports --bootstrap-server localhost:9092 --from-beginning
```

### Monitor Instructions (Terminal 2)
```bash
kafka-console-consumer.sh --topic instructions --bootstrap-server localhost:9092 --from-beginning
```

## Log Levels

Enable debug logging in `logback.xml`:
```xml
<logger name="observer" level="DEBUG"/>
```

## Integration Checklist

- [ ] Ensure Kafka is running
- [ ] Create `reports` and `instructions` topics
- [ ] Start Observer Service
- [ ] Configure Engine to send scores to `reports` topic
- [ ] Configure Factory to consume from `instructions` topic
- [ ] Test end-to-end pipeline

## Common Issues

| Issue | Solution |
|-------|----------|
| Cannot connect to Kafka | Verify `bootstrap.servers` and Kafka is running |
| No instructions sent | Increase DEBUG logging, check `maxSlope` threshold |
| Too many instructions | Increase `guardN` or adjust `maxSlope` |
| Slow response | Decrease `k` for faster trend detection |

## Parameter Tuning Quick Guide

**If too many instructions**: 
```properties
maxSlope=-0.005    # Less sensitive
guardN=10          # Longer wait
k=20               # Smoother trends
```

**If too few instructions**:
```properties
maxSlope=-0.02     # More sensitive
guardN=2           # Shorter wait
k=5                # Quicker response
```

## File Locations

| File | Purpose |
|------|---------|
| `observer/README.md` | Complete documentation |
| `observer/INTEGRATION.md` | How to integrate with RTCEF |
| `observer/src/main/resources/observer.properties` | Default config |
| `observer/src/main/scala/observer/Main.scala` | Entry point |
| `observer/src/main/scala/observer/core/ObserverService.scala` | Algorithm |

## Commands

```bash
# Build
sbt observer/compile
sbt observer/assembly

# Test
sbt observer/test

# Examples
sbt 'observer/runMain observer.examples.ObserverExample'

# Run
java -jar observer/target/observer-0.6.0-SNAPSHOT.jar

# Clean
sbt observer/clean
```

## Architecture Diagram

```
Reports Topic
      ↓
KafkaReportsConsumer
      ↓
ScoringWindow (k=10)
      ↓
TrendAnalyzer (linear regression)
      ↓
ObserverService (Algorithm 1)
      ↓
KafkaInstructionSender
      ↓
Instructions Topic
```

## Useful Kafka Commands

```bash
# List topics
kafka-topics.sh --list --bootstrap-server localhost:9092

# Create topics
kafka-topics.sh --create --topic reports --bootstrap-server localhost:9092
kafka-topics.sh --create --topic instructions --bootstrap-server localhost:9092

# Delete topics
kafka-topics.sh --delete --topic reports --bootstrap-server localhost:9092

# Monitor topic
kafka-console-consumer.sh --topic reports --bootstrap-server localhost:9092 --from-beginning
```

## Next Steps

1. **Read** `observer/README.md` for complete documentation
2. **Read** `observer/INTEGRATION.md` for system integration
3. **Review** `observer/src/main/scala/observer/examples/ObserverExample.scala` for code examples
4. **Configure** Observer for your environment
5. **Integrate** with Engine and Factory services
6. **Test** with sample score streams
7. **Deploy** to production

---

**For more details, see INTEGRATION.md and README.md**
