package observer.examples

import observer.core.ObserverService
import observer.model.ObserverConfig
import observer.analytics.TrendAnalyzer
import observer.state.ScoringWindow

/**
  * Example demonstrating the Observer Service components
  */
object ObserverExample {

  def main(args: Array[String]): Unit = {
    println("=" * 60)
    println("RTCEF Observer Service - Component Examples")
    println("=" * 60)

    // Example 1: TrendAnalyzer
    println("\n1. Trend Analysis Example")
    println("-" * 60)
    
    val scores1 = List(0.9, 0.85, 0.8, 0.75, 0.7)
    println(s"Scores (declining trend): $scores1")
    val (slope1, intercept1) = TrendAnalyzer.fitTrend(scores1)
    println(f"Fitted trend: slope=$slope1%.4f, intercept=$intercept1%.4f")
    println(s"Interpretation: Scores declining by ~${Math.abs(slope1)}%.2f per step")
    
    val rSquared1 = TrendAnalyzer.calculateRSquared(scores1, slope1, intercept1)
    println(f"R-squared: $rSquared1%.4f (fit quality)")

    // Example 2: ScoringWindow
    println("\n2. Scoring Window Example")
    println("-" * 60)
    
    val window = new ScoringWindow(k = 5)
    println("Adding scores to window with k=5:")
    
    val newScores = List(0.7, 0.72, 0.74, 0.73, 0.75, 0.76)
    for (score <- newScores) {
      window.update(score)
      println(f"  Added $score: window=${window.getScores}")
    }
    
    println(f"\nWindow size: ${window.size}")
    println(f"Latest score: ${window.getLatestScore}")

    // Example 3: Decision Logic Simulation
    println("\n3. Observer Decision Logic Simulation")
    println("-" * 60)
    
    simulateObserverLogic()

    println("\n" + "=" * 60)
    println("Examples completed!")
    println("=" * 60)
  }

  private def simulateObserverLogic(): Unit = {
    val config = ObserverConfig(
      k = 10,
      guardN = 5,
      maxSlope = -0.01,
      minScore = 0.5
    )

    println(s"Configuration:")
    println(s"  k (window size) = ${config.k}")
    println(s"  guard_n = ${config.guardN}")
    println(s"  max_slope = ${config.maxSlope}")
    println(s"  min_score = ${config.minScore}")

    // Simulate score sequence
    val scoreSequence = List(
      0.85, 0.84, 0.83, 0.82, 0.81,  // Declining trend
      0.80, 0.79, 0.78, 0.77, 0.76,
      0.75, 0.74, 0.73, 0.72, 0.71,
      0.45                             // Pit condition
    )

    println("\nSimulating score stream and decision making:")
    val window = new ScoringWindow(config.k)
    var guard = -1
    var instructionsSent = 0

    for ((score, idx) <- scoreSequence.zipWithIndex) {
      println(f"\nStep ${idx + 1}: Score = $score%.2f")

      // Update window
      window.update(score)

      // Check pit condition
      val pitCond = score < config.minScore
      println(f"  Pit condition (score < ${config.minScore}): $pitCond")

      // Check slope condition
      var slopeCond = false
      if (window.hasEnoughScores(3)) {
        val (slope, _) = TrendAnalyzer.fitTrend(window.getScores)
        slopeCond = slope < config.maxSlope
        println(f"  Slope: $slope%.4f, Slope condition (< ${config.maxSlope}): $slopeCond")
      } else {
        println(f"  Not enough scores for trend analysis (have ${window.size}, need 3)")
      }

      // Decrement guard
      if (guard >= 0) {
        guard = guard - 1
        println(f"  Guard decremented: $guard")
      }

      // Make decision
      if ((slopeCond && guard >= 0) || pitCond) {
        println(f"  *** OPTIMIZE instruction sent ***")
        guard = config.guardN
        instructionsSent += 1
      } else if (slopeCond) {
        println(f"  *** RETRAIN instruction sent ***")
        guard = config.guardN
        instructionsSent += 1
      } else {
        println("  No action")
      }
    }

    println(f"\nSimulation Summary:")
    println(f"  Total scores processed: ${scoreSequence.length}")
    println(f"  Instructions sent: $instructionsSent")
  }
}
