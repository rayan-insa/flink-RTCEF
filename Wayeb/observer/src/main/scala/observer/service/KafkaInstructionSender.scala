package observer.service

import java.util.Properties
import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import observer.model.{Instruction, ObserverConfig}

/**
  * Sends instructions to a Kafka topic
  * Handles the communication with the Factory service
  */
class KafkaInstructionSender(config: ObserverConfig) extends LazyLogging {

  private val producer: KafkaProducer[String, String] = setupProducer()

  private def setupProducer(): KafkaProducer[String, String] = {
    val props = new Properties()
    props.put("bootstrap.servers", config.kafkaBootstrapServers)
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("acks", "all")
    props.put("retries", "3")
    props.put("linger.ms", "10")

    new KafkaProducer[String, String](props)
  }

  /**
    * Send an instruction to the instructions topic
    *
    * @param instruction The instruction to send
    * @return True if successful, False otherwise
    */
  def sendInstruction(instruction: Instruction): Boolean = {
    try {
      val record = new ProducerRecord[String, String](
        config.instructionsTopicName,
        instruction.instructionType.value,
        instruction.toJson
      )
      
      val metadata = producer.send(record).get()
      logger.info(s"Sent instruction: ${instruction.instructionType.value} " +
        s"to topic '${config.instructionsTopicName}' partition ${metadata.partition()} " +
        s"offset ${metadata.offset()}")
      true
    } catch {
      case e: Exception =>
        logger.error(s"Failed to send instruction: ${instruction.toJson}", e)
        false
    }
  }

  /**
    * Close the producer and clean up resources
    */
  def close(): Unit = {
    try {
      producer.flush()
      producer.close()
      logger.info("KafkaInstructionSender closed")
    } catch {
      case e: Exception =>
        logger.warn(s"Error closing producer: ${e.getMessage}")
    }
  }
}
