# Integration Guide: Observer Service with RTCEF Framework

This guide explains how to integrate the Observer Service with the complete RTCEF framework for run-time model adaptation.

## System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        INPUT STREAM                             │
└───────────┬──────────────────────────────┬──────────────────────┘
            │                              │
            ▼                              ▼
        ┌────────┐                    ┌─────────┐
        │ ENGINE │                    │COLLECTOR│
        │(Wayeb)│◄──────┬──────────┤
        │        │      │          │
        └────┬───┘      │          │
             │          │          │
             │ Scores   │          │ Datasets
             ▼          │          ▼
        ┌──────────┐    │    ┌──────────┐
        │ Reports  │    │    │ Training │
        │ Topic    │    │    │ Data     │
        │(Kafka)   │    │    │          │
        └────┬─────┘    │    └──────────┘
             │          │
             ▼          │
        ┌──────────────┐│
        │ OBSERVER     ││
        │ (This Service)││
        └──────┬────────┘│
               │         │
               ▼         ▼
          ┌─────────────────────┐
          │ Instructions Topic  │
          │ (Kafka)             │
          └──────┬──────────────┘
                 │
                 ▼
            ┌──────────┐
            │ FACTORY  │
            │          │
            └────┬─────┘
                 │
                 ▼ (New Model)
            ┌──────────────┐
            │ Model Store  │
            │ (PST Models) │
            └──────┬───────┘
                   │
                   ▼ (Reload)
              ┌─────────┐
              │ ENGINE  │ (Update)
              └─────────┘
```

## Component Responsibilities

### 1. Engine (Wayeb)
- Processes incoming events
- Uses current PST model for Complex Event Forecasting
- Produces MCC scores → `reports` topic
- Listens for model updates

### 2. Observer (This Service)
- Consumes MCC scores from `reports` topic
- Analyzes trends in performance
- Decides between retrain vs. optimize
- Sends instructions → `instructions` topic

### 3. Factory
- Consumes instructions from `instructions` topic
- Retrains model (same hyperparameters) for RETRAIN
- Optimizes hyperparameters (Bayesian optimization) for OPTIMIZE
- Stores new model in model store
- Notifies Engine of model availability

### 4. Collector
- Maintains sliding window of events
- Provides training/test datasets
- Supports different sliding window strategies

## Kafka Topics Setup

Create three main topics for RTCEF:

```bash
# Create reports topic (Engine -> Observer)
kafka-topics.sh --create \
  --topic reports \
  --partitions 1 \
  --replication-factor 1 \
  --bootstrap-server localhost:9092

# Create instructions topic (Observer -> Factory)
kafka-topics.sh --create \
  --topic instructions \
  --partitions 1 \
  --replication-factor 1 \
  --bootstrap-server localhost:9092

# Optionally: Create control topic for model updates (Factory -> Engine)
kafka-topics.sh --create \
  --topic model-updates \
  --partitions 1 \
  --replication-factor 1 \
  --bootstrap-server localhost:9092
```

## Message Formats

### Reports Topic (Engine → Observer)

**Format**: Plain numeric MCC score

```
Message Key: <model_version>
Message Value: <mcc_score>

Example:
Key: v1
Value: 0.7523
```

The Observer expects simple double values. The engine should compute the MCC score and send it as a string representation.

**Scala code in Engine to produce scores**:

```scala
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}

val props = new Properties()
props.put("bootstrap.servers", "localhost:9092")
props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")

val producer = new KafkaProducer[String, String](props)

// After computing MCC score
val mccScore: Double = calculateMCC(...)
val record = new ProducerRecord[String, String](
  "reports",
  s"model-$modelVersion",
  mccScore.toString
)
producer.send(record)
```

### Instructions Topic (Observer → Factory)

**Format**: JSON

```json
{
  "type": "retrain|optimize",
  "timestamp": <unix_millis>,
  "triggeredBy": "<reason>"
}
```

**Example - Retrain**:
```json
{
  "type": "retrain",
  "timestamp": 1672531200000,
  "triggeredBy": "slope_condition"
}
```

**Example - Optimize**:
```json
{
  "type": "optimize",
  "timestamp": 1672531200000,
  "triggeredBy": "pit_condition"
}
```

The Observer produces these messages automatically.

**Scala code in Factory to consume instructions**:

```scala
import org.apache.kafka.clients.consumer.KafkaConsumer
import scala.collection.JavaConverters._
import play.api.libs.json._

val consumer = new KafkaConsumer[String, String](props)
consumer.subscribe(java.util.Arrays.asList("instructions"))

while (true) {
  val records = consumer.poll(Duration.ofMillis(1000))
  
  for (record <- records.asScala) {
    val json = Json.parse(record.value())
    val instructionType = (json \ "type").as[String]
    val triggeredBy = (json \ "triggeredBy").as[String]
    
    instructionType match {
      case "retrain" => retrainModel(triggeredBy)
      case "optimize" => optimizeModel(triggeredBy)
      case _ => logger.warn(s"Unknown instruction type: $instructionType")
    }
  }
}
```

## Complete Deployment Example

### Prerequisites

1. Kafka running on `localhost:9092`
2. Wayeb engine built: `sbt cef/assembly`
3. Observer service built: `sbt observer/assembly`

### Step 1: Start Kafka

```bash
# Terminal 1: Start Zookeeper
bin/zookeeper-server-start.sh config/zookeeper.properties

# Terminal 2: Start Kafka broker
bin/kafka-server-start.sh config/server.properties

# Terminal 3: Create topics
bin/kafka-topics.sh --create --topic reports --bootstrap-server localhost:9092
bin/kafka-topics.sh --create --topic instructions --bootstrap-server localhost:9092
```

### Step 2: Start Observer Service

```bash
# Terminal 4
cd Wayeb
java -jar observer/target/observer-0.6.0-SNAPSHOT.jar observer/src/main/resources/observer.properties
```

You should see logs:
```
[INFO] Starting RTCEF Observer Service
[INFO] Loading configuration from: observer/src/main/resources/observer.properties
[INFO] Configuration:
[INFO]   Kafka Bootstrap Servers: localhost:9092
[INFO]   Reports Topic: reports
[INFO]   Instructions Topic: instructions
[INFO]   Window Size (k): 10
...
[INFO] Started polling for scores
```

### Step 3: Start Engine with Score Publishing

Modify the Wayeb engine to publish scores to the `reports` topic. See the engine integration section below.

### Step 4: Monitor Topics (Optional)

```bash
# Monitor reports topic
kafka-console-consumer.sh --topic reports --from-beginning --bootstrap-server localhost:9092

# Monitor instructions topic  
kafka-console-consumer.sh --topic instructions --from-beginning --bootstrap-server localhost:9092
```

### Step 5: Start Factory Service (Not Included)

The Factory service is responsible for consuming instructions and generating new models. This needs to be implemented separately or integrated with an existing service.

## Engine Integration (Wayeb)

To integrate score publishing into the Wayeb engine:

### 1. Add Kafka Producer

In your engine's main execution loop (e.g., `ERFEngine.scala`):

```scala
// Add to dependencies if not already present
// "org.apache.kafka" % "kafka-clients" % "2.8.0"

class ERFEngine(...) {
  
  private var kafkaProducer: Option[KafkaProducer[String, String]] = None
  
  def setupKafkaProducer(bootstrapServers: String = "localhost:9092"): Unit = {
    val props = new Properties()
    props.put("bootstrap.servers", bootstrapServers)
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("acks", "all")
    props.put("retries", "3")
    
    kafkaProducer = Some(new KafkaProducer[String, String](props))
  }
  
  def publishScore(mccScore: Double, modelVersion: String = "current"): Unit = {
    kafkaProducer.foreach { producer =>
      val record = new ProducerRecord[String, String](
        "reports",
        modelVersion,
        mccScore.toString
      )
      producer.send(record)
    }
  }
  
  def runCEF(...): Unit = {
    // ... existing CEF logic ...
    
    // After computing MCC score
    val mccScore = computeMCCScore(...)
    publishScore(mccScore)
    
    // ... rest of CEF ...
  }
  
  def shutdown(): Unit = {
    kafkaProducer.foreach(_.close())
  }
}
```

### 2. Update Configuration

Add Kafka settings to your engine configuration:

```properties
# engine.properties
kafka.bootstrap.servers=localhost:9092
kafka.enabled=true
kafka.reports.topic=reports
```

## Factory Implementation Notes

While the Factory service is not provided in this package, here's what it should do:

### RETRAIN Instruction Processing

```scala
def retrainModel(instruction: Instruction): Unit = {
  logger.info("Processing RETRAIN instruction")
  
  // 1. Get latest training data from Collector
  val trainingData = getLatestTrainingData()
  
  // 2. Train new model with SAME hyperparameters
  val currentModel = loadCurrentModel()
  val newModel = trainModel(
    data = trainingData,
    hyperparams = currentModel.hyperparams  // Keep same!
  )
  
  // 3. Validate new model
  val validation = validateModel(newModel, trainingData)
  if (validation.isAcceptable) {
    // 4. Store new model
    saveModel(newModel)
    
    // 5. Notify Engine of update
    notifyEngineOfModelUpdate(newModel.version)
  }
}
```

### OPTIMIZE Instruction Processing

```scala
def optimizeModel(instruction: Instruction): Unit = {
  logger.info("Processing OPTIMIZE instruction")
  
  // 1. Get latest training data from Collector
  val trainingData = getLatestTrainingData()
  
  // 2. Run Bayesian optimization on hyperparameters
  val currentModel = loadCurrentModel()
  val optimizer = BayesianOptimizer(
    objective = (params) => trainAndValidate(trainingData, params),
    bounds = getHyperparameterBounds()
  )
  
  // 3. Find optimal hyperparameters
  val optimalParams = optimizer.optimize()
  
  // 4. Train model with optimized hyperparameters
  val newModel = trainModel(
    data = trainingData,
    hyperparams = optimalParams
  )
  
  // 5. Validate and store
  val validation = validateModel(newModel, trainingData)
  if (validation.isAcceptable) {
    saveModel(newModel)
    notifyEngineOfModelUpdate(newModel.version)
  }
}
```

## Monitoring and Tuning

### Key Metrics to Monitor

1. **Instruction Frequency**: Check if instructions are being sent appropriately
   - Too frequent: Reduce `maxSlope` sensitivity or increase `guardN`
   - Too rare: Increase `maxSlope` sensitivity or decrease `k`

2. **Score Trends**: Monitor the trend analysis results
   ```bash
   kafka-console-consumer.sh --topic reports --bootstrap-server localhost:9092 | head -20
   ```

3. **Guard Period Effectiveness**: Log when guard periods prevent redundant instructions

### Adjusting Parameters

**If Observer is too sensitive** (too many instructions):
```properties
maxSlope=-0.005        # Require worse trend to trigger
guardN=10              # Longer wait between instructions
k=20                   # Larger window for smoother trends
```

**If Observer is not sensitive enough** (too few instructions):
```properties
maxSlope=-0.02         # Easier to trigger
guardN=2               # Shorter wait between instructions
k=5                    # Smaller window for quicker response
minScore=0.7           # Lower tolerance for bad scores
```

## Troubleshooting Integration

### Observer not receiving scores

1. Check if Engine is publishing to `reports` topic
   ```bash
   kafka-console-consumer.sh --topic reports --from-beginning --bootstrap-server localhost:9092
   ```

2. Verify Kafka connection in Observer logs
   ```
   grep "Subscribed to topic" observer.log
   ```

### Observer not sending instructions

1. Check if scores are triggering conditions
   - Increase DEBUG logging in Observer
   - Verify score trend is actually declining

2. Check guard period
   - May be preventing frequent instructions
   - Reduce `guardN` for testing

### Factory not receiving instructions

1. Verify instruction topic has messages
   ```bash
   kafka-console-consumer.sh --topic instructions --from-beginning --bootstrap-server localhost:9092
   ```

2. Check Factory consumer group offset
   ```bash
   kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group factory-group --describe
   ```

## Performance Considerations

- **Observer latency**: ~200ms (Kafka poll + linear regression)
- **Instruction frequency**: Controlled by guard period + slope threshold
- **System throughput**: Can handle hundreds of scores per second
- **Memory footprint**: ~5-10MB for typical configurations

## Future Enhancements

- [ ] Multiple observer instances for horizontal scaling
- [ ] Feedback loop from Factory to Observer (instruction success rate)
- [ ] Alternative trend analysis algorithms
- [ ] Adaptive parameter tuning based on instruction outcomes
- [ ] Metrics publishing to Prometheus
- [ ] Model versioning and rollback support
- [ ] A/B testing of new models before deployment

## References

- RTCEF Paper: "Run-Time Adaptation of Complex Event Forecasting"
- Wayeb Documentation: See `Wayeb/docs/`
- Kafka Documentation: https://kafka.apache.org/documentation/
