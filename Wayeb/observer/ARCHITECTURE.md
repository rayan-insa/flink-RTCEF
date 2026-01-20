# Observer Service - Architecture & Design Document

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    RTCEF Complete Pipeline                      │
└─────────────────────────────────────────────────────────────────┘

Engine (Wayeb)              Observer Service (This)         Factory Service
      │                            │                              │
      │ Processes Events           │                              │
      │ Computes MCC Scores        │                              │
      │                            │                              │
      ├──────────────────────┐     │                              │
      │                     │     │                              │
      │ Score 0.75          │     │                              │
      │ Score 0.74          │     │                              │
      │ Score 0.73          │     │                              │
      └─────────────────────┼─────┼──────────────────┐           │
                            │     │ Reports Topic    │           │
                            │     │ (Kafka)          │           │
                            │     └──────────────────┘           │
                            │                                     │
                            │  1. Consume Score                  │
                            │  2. Update Window                  │
                            │  3. Analyze Trend                  │
                            │  4. Make Decision                  │
                            │                                     │
                            ├──────────────────────┐             │
                            │                     │             │
                            │ RETRAIN Instruction │             │
                            │ OPTIMIZE Instruction│             │
                            │                     │             │
                            └─────────────────────┼─────────────┼──┐
                                                  │             │  │
                                         Instructions Topic    │  │
                                         (Kafka)              │  │
                                                              │  │
                                    5. Consume Instruction   │  │
                                    6. Train/Optimize Model  │  │
                                    7. Store New Model       │  │
                                    8. Notify Engine         │  │
                                                              │  │
                                          Model-Updates Topic │  │
                                          (Optional)          │  │
                                                              │  │
                            ┌─────────────────────────────────┘  │
                            │                                      │
                            ▼                                      │
                        Load New Model                             │
                        Update Engine                              │
                        Resume CEF                                 │
```

## Component Interaction Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                      Observer Service                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Main                                                           │
│   │                                                             │
│   ├─► Configuration Loading                                   │
│   │     └─► ObserverConfig                                    │
│   │                                                             │
│   └─► ObserverService                                         │
│        │                                                        │
│        ├─► KafkaReportsConsumer                               │
│        │    │                                                  │
│        │    └─► Kafka Bootstrap                               │
│        │         Subscribe to: reports topic                  │
│        │                                                        │
│        ├─► Event Loop                                         │
│        │    │                                                  │
│        │    ├─► Poll Score from Kafka                         │
│        │    │                                                  │
│        │    ├─► ScoringWindow.update(score)                  │
│        │    │    │                                             │
│        │    │    └─► Maintain Sliding Window (size k)         │
│        │    │                                                  │
│        │    ├─► Check Pit Condition                           │
│        │    │    └─► score < minScore?                        │
│        │    │                                                  │
│        │    ├─► TrendAnalyzer.fitTrend(scores)               │
│        │    │    │                                             │
│        │    │    └─► Linear Regression                        │
│        │    │         Calculate slope                          │
│        │    │         slope < maxSlope?                        │
│        │    │                                                  │
│        │    └─► Decision Logic                                │
│        │         if pit_cond → OPTIMIZE                       │
│        │         if slope_cond:                               │
│        │           if guard_active → OPTIMIZE                 │
│        │           else → RETRAIN                             │
│        │                                                        │
│        └─► KafkaInstructionSender                             │
│             │                                                  │
│             └─► Send Instruction to: instructions topic       │
│                  Format: JSON                                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## Data Flow Diagram

```
Score Stream
    │
    │ 0.85
    │ 0.84
    │ 0.83
    │ 0.82
    │ 0.81
    │
    ▼
ScoringWindow
[0.85, 0.84, 0.83, 0.82, 0.81]
    │
    ├─► Latest: 0.81
    ├─► Size: 5
    └─► Has Enough (>2): Yes
         │
         ▼
    TrendAnalyzer
    Fit Trend to [0.85, 0.84, 0.83, 0.82, 0.81]
         │
         ├─► slope = -0.01
         ├─► intercept = 0.85
         └─► Compare: slope < max_slope(-0.01)?
                     No (same) → slope_cond = False
         │
         ▼
    Decision Logic
    pit_cond = 0.81 < 0.5? No
    slope_cond = slope < -0.01? No
         │
         ▼
    No Action
    Continue polling
```

## Algorithm State Machine

```
Initial State: guard = -1

┌──────────────────┐
│  Poll Score      │
└────────┬─────────┘
         │
         ▼
┌──────────────────────────┐
│ Update ScoringWindow     │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────┐
│ Check pit_cond           │
│ (score < minScore)       │
└────────┬─────────────────┘
         │
    ┌────┴─────┐
    │ True      │ False
    │           │
    │           ▼
    │      ┌─────────────────┐
    │      │ Decrement Guard │
    │      │ (if guard >= 0) │
    │      └────────┬────────┘
    │               │
    │               ▼
    │      ┌────────────────────┐
    │      │ Has Enough Scores? │
    │      │ (length > 2)       │
    │      └────────┬───────────┘
    │               │
    │           ┌───┴────┐
    │           │ Yes    │ No
    │           │        │
    │           ▼        ▼
    │      ┌─────────────────┐
    │      │ Fit Trend       │ → Continue
    │      │ Check slope     │
    │      └────────┬────────┘
    │               │
    │      ┌────────┴──────────┐
    │      │ slope_cond?       │
    │      │ (slope < max_slope)
    │      └────────┬──────────┘
    │               │
    │           ┌───┴──────┐
    │           │ True     │ False
    │           │          │
    │           ▼          ▼
    │      ┌─────────────────┐
    │      │ guard >= 0?     │ → Continue
    │      └────────┬────────┘
    │               │
    │           ┌───┴─────┐
    │      ┌────┘ True    │ False
    │      │              │
    ▼      ▼              ▼
OPTIMIZE  RETRAIN
guard =   guard =
guard_n   guard_n
```

## Class Relationships

```
ObserverConfig
    ├─ Contains all algorithm parameters
    ├─ Loaded from properties file
    └─ Used by ObserverService

ObserverService
    ├─ Uses: KafkaReportsConsumer (HAS-A)
    ├─ Uses: KafkaInstructionSender (HAS-A)
    ├─ Uses: ScoringWindow (HAS-A)
    ├─ Uses: TrendAnalyzer (USES static methods)
    └─ Uses: Instruction (CREATES)

KafkaReportsConsumer
    ├─ Returns: Option[Double]
    ├─ Internal: KafkaConsumer[String, String]
    └─ Parses: String → Double

KafkaInstructionSender
    ├─ Accepts: Instruction
    ├─ Internal: KafkaProducer[String, String]
    └─ Sends: JSON format

ScoringWindow
    ├─ Stores: List[Double]
    ├─ Methods: update, getScores, getLatestScore, hasEnoughScores
    └─ Capacity: k (configurable)

TrendAnalyzer
    ├─ Static method: fitTrend(scores: List[Double]) → (Double, Double)
    ├─ Static method: calculateRSquared(...) → Double
    └─ Uses: Least squares linear regression

Instruction
    ├─ Type: InstructionType (sealed trait)
    ├─ InstructionType: RetrainInstruction | OptimizeInstruction
    ├─ Fields: type, timestamp, triggeredBy
    └─ Method: toJson() → String
```

## Configuration Parameter Relationships

```
maxSlope ◄────────┐
    │              │
    │              ├── Control
    │              │   responsiveness
    ▼              │
Slope Condition    │
    │              │
    │              │
k (window size) ◄─┤
    │              │
    │              │
TrendAnalyzer ────┘
    │
    ▼
fit_trend()

    ┌────────────────────────────────────────┐
    │                                        │
    ▼                                        ▼
pit_cond                              slope_cond
    │                                        │
    │              ┌────────────────────────┘
    │              │
    ▼              ▼
Decision Logic

    ┌─────────────────────────────────────┐
    │                                     │
    ├─► (slope_cond AND guard >= 0) OR   │
    │   pit_cond                          │
    │   → OPTIMIZE                        │
    │   → guard = guardN                  │
    │                                     │
    └─► slope_cond AND guard < 0          │
        → RETRAIN                         │
        → guard = guardN                  │
```

## Execution Flow Diagram

```
START
  │
  ▼
Load Configuration
  │
  ├─ Read observer.properties
  ├─ Set k, guardN, maxSlope, minScore
  └─ Setup Kafka connection
  │
  ▼
Initialize Components
  │
  ├─ Create ScoringWindow(k)
  ├─ Create KafkaReportsConsumer
  ├─ Create KafkaInstructionSender
  └─ Set guard = -1
  │
  ▼
Main Event Loop (INFINITE)
  │
  ├─ [ Poll Score from Kafka ]
  │   └─► pollScore() → Option[Double]
  │
  ├─ [ For Each Score ]
  │   │
  │   ├─ Update ScoringWindow
  │   │   └─► scoringWindow.update(score)
  │   │
  │   ├─ Check Pit Condition
  │   │   └─► pit_cond = score < minScore
  │   │
  │   ├─ Initialize slope_cond = False
  │   │
  │   ├─ Decrement Guard
  │   │   └─► if (guard >= 0) guard = guard - 1
  │   │
  │   ├─ [ If Enough Scores ]
  │   │   │
  │   │   ├─ Fit Trend
  │   │   │   └─► (slope, intercept) = fitTrend(scores)
  │   │   │
  │   │   └─ Check Slope Condition
  │   │       └─► slope_cond = slope < maxSlope
  │   │
  │   └─ [ Decision ]
  │       │
  │       ├─ IF (slope_cond AND guard >= 0) OR pit_cond
  │       │   │
  │       │   ├─ Send OPTIMIZE
  │       │   ├─ Set guard = guardN
  │       │   └─ Log instruction
  │       │
  │       ├─ ELSE IF slope_cond
  │       │   │
  │       │   ├─ Send RETRAIN
  │       │   ├─ Set guard = guardN
  │       │   └─ Log instruction
  │       │
  │       └─ ELSE
  │           └─ Log (no action)
  │
  ▼
(Loop continues indefinitely)
```

## Error Handling

```
Score Consumption
  │
  ├─ Kafka Connection Error
  │   └─► Log Error, Continue
  │
  ├─ Invalid Score Format
  │   ├─► NumberFormatException
  │   └─► Log Warning, Skip Message
  │
  ├─ Consumer Poll Error
  │   └─► Log Error, Continue
  │
  └─ Success
      └─► Process Score

Trend Analysis
  │
  ├─ Less than 2 scores
  │   └─► IllegalArgumentException, Skip
  │
  ├─ All same scores
  │   └─► slope = 0
  │
  └─ Normal calculation
      └─► (slope, intercept) computed

Instruction Sending
  │
  ├─ Kafka Connection Error
  │   └─► Log Error, Return False
  │
  ├─ Producer Error
  │   └─► Log Error, Return False
  │
  └─ Success
      └─► Log Instruction Sent
```

## Resource Management

```
Initialization
  │
  ├─ Create KafkaConsumer
  ├─ Create KafkaProducer
  └─ Allocate ScoringWindow (fixed size)
  
Running
  │
  ├─ Consume messages (polling)
  ├─ Process in-memory (minimal allocation)
  └─ Produce messages (buffered)

Shutdown
  │
  ├─ Consumer.close()
  ├─ Producer.flush() + close()
  └─ Clear references
```

## Performance Characteristics

```
Per Score Processed
  │
  ├─ Kafka Poll: ~100ms (blocking)
  ├─ Update Window: O(1)
  ├─ Trend Analysis: O(k) where k=10
  ├─ Decision Logic: O(1)
  └─ Total: ~100-200ms per score
  
Memory Usage
  │
  ├─ ScoringWindow buffer: k * 8 bytes = 80 bytes
  ├─ Kafka consumer: ~2 MB
  ├─ Kafka producer: ~2 MB
  ├─ JVM overhead: ~1-2 MB
  └─ Total: ~5-10 MB

Throughput
  │
  ├─ Score consumption: Limited by Kafka poll timeout
  ├─ Instruction production: Depends on trend changes
  └─ Typical: 5-10 scores/second (with 100ms poll timeout)
```

## Testing Strategy

```
Unit Tests
  │
  ├─ TrendAnalyzer
  │   ├─ Test linear regression accuracy
  │   ├─ Test slope calculation
  │   ├─ Test edge cases (constant, linear, noisy)
  │   └─ Test R-squared computation
  │
  ├─ ScoringWindow
  │   ├─ Test window size limiting
  │   ├─ Test score ordering
  │   ├─ Test latest score
  │   └─ Test enough scores check
  │
  └─ Instruction
      ├─ Test serialization
      ├─ Test types
      └─ Test JSON format

Integration Tests
  │
  ├─ Kafka connectivity
  ├─ Message consumption/production
  ├─ End-to-end flow
  └─ Error handling

Examples
  │
  ├─ Component usage
  ├─ Configuration loading
  ├─ Decision logic simulation
  └─ Trend analysis examples
```

---

This architecture ensures:
- **Modularity**: Each component has clear responsibility
- **Testability**: Components can be tested independently
- **Scalability**: Minimal memory footprint, efficient processing
- **Reliability**: Error handling at all levels
- **Maintainability**: Clear separation of concerns
