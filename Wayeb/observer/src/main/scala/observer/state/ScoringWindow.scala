package observer.state

/**
  * Maintains a sliding window of recent scores
  * Similar to a circular buffer with size k
  *
  * @param maxSize Maximum number of scores to keep
  */
class ScoringWindow(maxSize: Int) {
  private var scores: List[Double] = List()

  /**
    * Add a new score to the window
    * If window is at capacity, removes the oldest score
    *
    * @param score The MCC score to add
    */
  def update(score: Double): Unit = {
    scores = scores :+ score
    if (scores.length > maxSize) {
      scores = scores.drop(1)
    }
  }

  /**
    * Get all scores in the current window
    *
    * @return List of scores in chronological order
    */
  def getScores: List[Double] = scores

  /**
    * Get the most recent score
    *
    * @return The last score added, or None if window is empty
    */
  def getLatestScore: Option[Double] = scores.lastOption

  /**
    * Check if window has enough scores for analysis
    *
    * @param minRequired Minimum number of scores required
    * @return True if window has at least minRequired scores
    */
  def hasEnoughScores(minRequired: Int = 2): Boolean = scores.length >= minRequired

  /**
    * Get the current size of the window
    *
    * @return Number of scores currently in the window
    */
  def size: Int = scores.length

  /**
    * Clear all scores from the window
    */
  def clear(): Unit = {
    scores = List()
  }

  override def toString: String = s"ScoringWindow(size=$size, scores=$scores)"
}
