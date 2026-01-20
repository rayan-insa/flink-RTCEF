package observer.core

import com.typesafe.scalalogging.LazyLogging
import observer.analytics.TrendAnalyzer
import observer.model.{Instruction, ObserverConfig, OptimizeInstruction, RetrainInstruction}
import observer.service.{KafkaInstructionSender, KafkaReportsConsumer}
import observer.state.ScoringWindow

/**
  * ObserverService implements Algorithm 1 from the RTCEF paper
  * Monitors MCC scores and decides between retraining or optimization
  * 
  * Algorithm 1: Observer service
  * Require: k, guard_n, max_slope, min_score
  * 1: scores ← []
  * 2: guard ← −1
  * 3: while True do
  * 4:   score_i ← consume(Reports)
  * 5:   scores.update(score_i, k)
  * 6:   pit_cond ← score_i < min_score
  * 7:   slope_cond ← False
  * 8:   if guard ≥ 0 then guard ← guard − 1
  * 9:   if len(|scores|) > 2 then
  * 10:    (a_i, b_i) ← fit_trend(scores)
  * 11:    slope_cond ← a_i < max_slope
  * 12:  if (slope_cond and guard ≥ 0) or pit_cond then
  * 13:    send("instructions", "optimize")
  * 14:    guard ← guard_n
  * 15:  else if slope_cond then
  * 16:    send("instructions", "retrain")
  * 17:    guard ← guard_n
  */
class ObserverService(config: ObserverConfig) extends LazyLogging {

  private val scoringWindow = new ScoringWindow(config.k)
  private var guard = -1
  private val consumer = new KafkaReportsConsumer(config)
  private val sender = new KafkaInstructionSender(config)

  /**
    * Run the observer service continuously
    * This is the main event loop that implements Algorithm 1
    */
  def run(): Unit = {
    logger.info(s"Starting ObserverService with config: $config")
    
    try {
      while (true) {
        // Step 4: Consume score from Reports topic
        consumer.pollScore() match {
          case Some(scoreI) =>
            logger.debug(s"Processing score: $scoreI")
            
            // Step 5: Update sliding window
            scoringWindow.update(scoreI)
            
            // Step 6: Check pit condition (single score too low)
            val pitCond = scoreI < config.minScore
            
            // Step 7: Initialize slope condition
            var slopeCond = false
            
            // Step 8: Decrement guard if active
            if (guard >= 0) {
              guard = guard - 1
            }
            
            // Step 9-11: Check trend condition if we have enough scores
            if (scoringWindow.hasEnoughScores(3)) {
              val scores = scoringWindow.getScores
              val (slope, intercept) = TrendAnalyzer.fitTrend(scores)
              
              logger.debug(s"Trend analysis: slope=$slope, intercept=$intercept, " +
                s"maxSlope=${config.maxSlope}, scores=${scores.length}")
              
              // Step 11: Check if slope is worse than threshold
              slopeCond = slope < config.maxSlope
            }
            
            // Step 12-17: Make decision based on conditions
            if ((slopeCond && guard >= 0) || pitCond) {
              // Step 13-14: Send optimize instruction
              val trigger = if (pitCond) "pit_condition" else "slope_and_guard"
              val instruction = Instruction.optimize(s"Triggered by: $trigger")
              sender.sendInstruction(instruction)
              guard = config.guardN
              logger.info(s"Sent OPTIMIZE instruction (trigger: $trigger)")
              
            } else if (slopeCond) {
              // Step 16-17: Send retrain instruction
              val instruction = Instruction.retrain("slope_condition")
              sender.sendInstruction(instruction)
              guard = config.guardN
              logger.info("Sent RETRAIN instruction")
            } else {
              logger.debug(s"No action needed. slopeCond=$slopeCond, guard=$guard, pitCond=$pitCond")
            }
            
          case None =>
            // No message available, continue
            logger.trace("No score available from Reports topic")
        }
      }
    } catch {
      case e: Exception =>
        logger.error(s"Fatal error in ObserverService: ${e.getMessage}", e)
        throw e
    } finally {
      close()
    }
  }

  /**
    * Run the observer service with continuous threading
    * Allows the service to run indefinitely
    *
    * @return The thread that was started
    */
  def runAsync(): Thread = {
    val thread = new Thread(() => {
      try {
        run()
      } catch {
        case e: Exception =>
          logger.error(s"Observer service thread terminated with error: ${e.getMessage}", e)
      }
    })
    thread.setName("ObserverService")
    thread.setDaemon(false)
    thread.start()
    thread
  }

  /**
    * Close all resources
    */
  def close(): Unit = {
    try {
      consumer.close()
      sender.close()
      logger.info("ObserverService closed")
    } catch {
      case e: Exception =>
        logger.warn(s"Error closing resources: ${e.getMessage}")
    }
  }

  /**
    * Get current state for monitoring/debugging
    */
  def getState: ObserverState = {
    ObserverState(
      currentScores = scoringWindow.getScores,
      latestScore = scoringWindow.getLatestScore,
      guardCounter = guard,
      windowSize = scoringWindow.size
    )
  }
}

/**
  * Current state of the observer for monitoring
  */
case class ObserverState(
  currentScores: List[Double],
  latestScore: Option[Double],
  guardCounter: Int,
  windowSize: Int
) {
  override def toString: String = 
    s"ObserverState(windowSize=$windowSize, latestScore=$latestScore, guard=$guardCounter)"
}
