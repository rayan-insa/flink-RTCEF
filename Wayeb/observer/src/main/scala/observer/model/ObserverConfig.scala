package observer.model

/**
  * Configuration for the Observer Service
  *
  * @param kafkaBootstrapServers Kafka bootstrap servers (e.g., "localhost:9092")
  * @param reportsTopicName Topic to consume MCC scores from
  * @param instructionsTopicName Topic to send instructions to
  * @param groupId Kafka consumer group ID
  * @param k Size of the sliding window for score tracking
  * @param guardN Guard period duration (number of updates)
  * @param maxSlope Maximum acceptable slope for trend analysis
  * @param minScore Minimum acceptable MCC score threshold
  * @param pollTimeoutMs Timeout for polling Kafka messages (milliseconds)
  */
case class ObserverConfig(
  kafkaBootstrapServers: String = "localhost:9092",
  reportsTopicName: String = "reports",
  instructionsTopicName: String = "instructions",
  groupId: String = "observer-group",
  k: Int = 10,
  guardN: Int = 5,
  maxSlope: Double = -0.01,
  minScore: Double = 0.5,
  pollTimeoutMs: Long = 1000L
)

object ObserverConfig {
  def fromProperties(props: java.util.Properties): ObserverConfig = {
    ObserverConfig(
      kafkaBootstrapServers = props.getProperty("bootstrap.servers", "localhost:9092"),
      reportsTopicName = props.getProperty("reportsTopic", "reports"),
      instructionsTopicName = props.getProperty("instructionsTopic", "instructions"),
      groupId = props.getProperty("group.id", "observer-group"),
      k = props.getProperty("k", "10").toInt,
      guardN = props.getProperty("guardN", "5").toInt,
      maxSlope = props.getProperty("maxSlope", "-0.01").toDouble,
      minScore = props.getProperty("minScore", "0.5").toDouble,
      pollTimeoutMs = props.getProperty("pollTimeoutMs", "1000").toLong
    )
  }
}
