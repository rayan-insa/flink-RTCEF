package observer.service

import java.io.{File, FileReader}
import java.time.Duration
import java.util.Properties
import scala.collection.JavaConverters._
import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import observer.model.ObserverConfig

/**
  * Consumes MCC scores from the Reports Kafka topic
  */
class KafkaReportsConsumer(config: ObserverConfig, propertiesFile: Option[String] = None) extends LazyLogging {

  private val consumer: KafkaConsumer[String, String] = setupConsumer()
  private var isRunning = false

  private def setupConsumer(): KafkaConsumer[String, String] = {
    val props = new Properties()
    
    // Load from properties file if provided
    propertiesFile.foreach { file =>
      val f = new File(file)
      if (f.exists()) {
        props.load(new FileReader(f))
        logger.info(s"Loaded Kafka configuration from $file")
      }
    }

    // Override with explicit config values
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafkaBootstrapServers)
    props.put(ConsumerConfig.GROUP_ID_CONFIG, config.groupId)
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, 
      "org.apache.kafka.common.serialization.StringDeserializer")
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
      "org.apache.kafka.common.serialization.StringDeserializer")
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")

    val consumer = new KafkaConsumer[String, String](props)
    consumer.subscribe(java.util.Arrays.asList(config.reportsTopicName))
    logger.info(s"Subscribed to topic '${config.reportsTopicName}'")
    
    consumer
  }

  /**
    * Poll for the next score from the Reports topic
    * Blocks until a message is available or timeout is reached
    *
    * @return Option containing the score as Double, or None if no message available
    */
  def pollScore(): Option[Double] = {
    try {
      val records = consumer.poll(Duration.ofMillis(config.pollTimeoutMs))
      
      if (records.isEmpty) {
        None
      } else {
        val record = records.iterator().next()
        try {
          val score = record.value().toDouble
          logger.debug(s"Consumed score: $score from topic '${config.reportsTopicName}'")
          Some(score)
        } catch {
          case e: NumberFormatException =>
            logger.warn(s"Failed to parse score from message: '${record.value()}': ${e.getMessage}")
            None
        }
      }
    } catch {
      case e: Exception =>
        logger.error(s"Error polling from Kafka: ${e.getMessage}", e)
        None
    }
  }

  /**
    * Start continuous polling in a separate thread
    * Calls the callback function for each score received
    *
    * @param callback Function to call with each score
    * @return The thread that was started
    */
  def startPolling(callback: Double => Unit): Thread = {
    val thread = new Thread(() => {
      isRunning = true
      logger.info("Started polling for scores")
      
      while (isRunning) {
        pollScore().foreach(callback)
      }
      
      logger.info("Stopped polling for scores")
    })
    thread.setName("KafkaReportsConsumer")
    thread.start()
    thread
  }

  /**
    * Stop polling for scores
    */
  def stop(): Unit = {
    isRunning = false
  }

  /**
    * Close the consumer and clean up resources
    */
  def close(): Unit = {
    try {
      consumer.close()
      logger.info("KafkaReportsConsumer closed")
    } catch {
      case e: Exception =>
        logger.warn(s"Error closing consumer: ${e.getMessage}")
    }
  }
}
