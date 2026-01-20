package observer.analytics

/**
  * Performs linear regression (trend fitting) on score data
  * Returns (slope, intercept) tuple where slope represents the trend
  */
object TrendAnalyzer {

  /**
    * Fit a linear trend to a sequence of scores using least squares method
    * Returns tuple (slope, intercept) of the line y = slope * x + intercept
    *
    * @param scores List of scores to fit
    * @return Tuple of (slope, intercept)
    */
  def fitTrend(scores: List[Double]): (Double, Double) = {
    if (scores.length < 2) {
      throw new IllegalArgumentException("At least 2 scores are required for trend analysis")
    }

    val n = scores.length
    val x = (0 until n).map(_.toDouble).toList
    val y = scores

    // Calculate means
    val xMean = x.sum / n
    val yMean = y.sum / n

    // Calculate slope: sum((x - xMean) * (y - yMean)) / sum((x - xMean)^2)
    val numerator = x.zip(y).map { case (xi, yi) => (xi - xMean) * (yi - yMean) }.sum
    val denominator = x.map { xi => Math.pow(xi - xMean, 2) }.sum

    if (denominator == 0) {
      (0.0, yMean) // Flat line
    } else {
      val slope = numerator / denominator
      val intercept = yMean - slope * xMean
      (slope, intercept)
    }
  }

  /**
    * Calculate R-squared value to measure goodness of fit
    * 
    * @param scores Actual scores
    * @param slope Linear regression slope
    * @param intercept Linear regression intercept
    * @return R-squared value (0 to 1, higher is better)
    */
  def calculateRSquared(scores: List[Double], slope: Double, intercept: Double): Double = {
    val n = scores.length
    val yMean = scores.sum / n

    // Sum of squares of residuals
    val ssRes = scores.zipWithIndex.map { case (yi, i) =>
      val predicted = slope * i + intercept
      Math.pow(yi - predicted, 2)
    }.sum

    // Total sum of squares
    val ssTot = scores.map { yi => Math.pow(yi - yMean, 2) }.sum

    if (ssTot == 0) 1.0 else 1.0 - (ssRes / ssTot)
  }
}
