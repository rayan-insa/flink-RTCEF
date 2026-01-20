package observer.analytics

import org.scalatest.FunSuite

class TrendAnalyzerSpec extends FunSuite {

  test("fitTrend should calculate correct slope and intercept for simple increasing trend") {
    val scores = List(0.5, 0.6, 0.7, 0.8, 0.9)
    val (slope, intercept) = TrendAnalyzer.fitTrend(scores)
    
    // Slope should be ~0.1 (increasing by 0.1 each step)
    assert(slope > 0.09 && slope < 0.11, s"Expected slope ~0.1, got $slope")
    assert(intercept > 0.4 && intercept < 0.51, s"Expected intercept ~0.5, got $intercept")
  }

  test("fitTrend should detect negative slope (declining trend)") {
    val scores = List(0.9, 0.8, 0.7, 0.6, 0.5)
    val (slope, intercept) = TrendAnalyzer.fitTrend(scores)
    
    // Slope should be ~-0.1 (declining by 0.1 each step)
    assert(slope < -0.09 && slope > -0.11, s"Expected slope ~-0.1, got $slope")
  }

  test("fitTrend should handle constant values (zero slope)") {
    val scores = List(0.7, 0.7, 0.7, 0.7, 0.7)
    val (slope, intercept) = TrendAnalyzer.fitTrend(scores)
    
    assert(Math.abs(slope) < 0.01, s"Expected slope ~0, got $slope")
    assert(Math.abs(intercept - 0.7) < 0.01, s"Expected intercept ~0.7, got $intercept")
  }

  test("fitTrend should throw exception for less than 2 scores") {
    intercept[IllegalArgumentException] {
      TrendAnalyzer.fitTrend(List(0.5))
    }
  }

  test("calculateRSquared should return value between 0 and 1") {
    val scores = List(1.0, 2.0, 3.0, 4.0, 5.0)
    val (slope, intercept) = TrendAnalyzer.fitTrend(scores)
    val rSquared = TrendAnalyzer.calculateRSquared(scores, slope, intercept)
    
    assert(rSquared >= 0 && rSquared <= 1, s"R-squared should be between 0 and 1, got $rSquared")
  }

  test("calculateRSquared should be 1 for perfect fit") {
    val scores = List(1.0, 2.0, 3.0, 4.0, 5.0)
    val slope = 1.0
    val intercept = 0.0
    val rSquared = TrendAnalyzer.calculateRSquared(scores, slope, intercept)
    
    assert(Math.abs(rSquared - 1.0) < 0.01, s"R-squared should be ~1 for perfect fit, got $rSquared")
  }
}
