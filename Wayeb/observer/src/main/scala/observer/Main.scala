package observer

import java.io.File
import scala.io.Source
import com.typesafe.scalalogging.LazyLogging
import observer.core.ObserverService
import observer.model.ObserverConfig

/**
  * Main entry point for the Observer Service
  * 
  * Usage: scala observer.Main [config_file]
  * 
  * If no config file is provided, default configuration will be used.
  * Configuration can also be provided via environment variables or system properties.
  */
object Main extends LazyLogging {

  def main(args: Array[String]): Unit = {
    try {
      logger.info("=" * 60)
      logger.info("Starting RTCEF Observer Service")
      logger.info("=" * 60)

      // Load configuration
      val config = if (args.nonEmpty) {
        val configFile = args(0)
        logger.info(s"Loading configuration from: $configFile")
        loadConfigFromFile(configFile)
      } else {
        logger.info("Using default configuration")
        ObserverConfig()
      }

      logger.info(s"""
        |Configuration:
        |  Kafka Bootstrap Servers: ${config.kafkaBootstrapServers}
        |  Reports Topic: ${config.reportsTopicName}
        |  Instructions Topic: ${config.instructionsTopicName}
        |  Consumer Group ID: ${config.groupId}
        |  Window Size (k): ${config.k}
        |  Guard Period (guard_n): ${config.guardN}
        |  Max Slope Threshold: ${config.maxSlope}
        |  Min Score Threshold: ${config.minScore}
        |  Poll Timeout (ms): ${config.pollTimeoutMs}
        |""".stripMargin)

      // Create and run observer service
      val observer = new ObserverService(config)
      
      // Add shutdown hook for graceful shutdown
      Runtime.getRuntime.addShutdownHook(new Thread(() => {
        logger.info("Shutting down Observer Service...")
        observer.close()
        logger.info("Observer Service stopped")
      }))

      // Run the service (blocking)
      observer.run()

    } catch {
      case e: Exception =>
        logger.error(s"Fatal error: ${e.getMessage}", e)
        System.exit(1)
    }
  }

  /**
    * Load configuration from a properties file
    *
    * @param filePath Path to the configuration file
    * @return ObserverConfig instance
    */
  private def loadConfigFromFile(filePath: String): ObserverConfig = {
    val file = new File(filePath)
    if (!file.exists()) {
      throw new IllegalArgumentException(s"Configuration file not found: $filePath")
    }

    val props = new java.util.Properties()
    val fileReader = Source.fromFile(file)
    try {
      props.load(fileReader.bufferedReader())
      ObserverConfig.fromProperties(props)
    } finally {
      fileReader.close()
    }
  }
}
