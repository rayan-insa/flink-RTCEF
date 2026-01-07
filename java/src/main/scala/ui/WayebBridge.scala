package ui

object WayebBridge {
  /**
   * Java-friendly entry point for training.
   * Handles WayebConfig construction internally to avoid Java-Scala interop pain.
   */
  def train(
      datasetPath: String, 
      outputModelPath: String, 
      patternPath: String, 
      pMin: Double, 
      gamma: Double, 
      alpha: Double, 
      r: Double
  ): Unit = {
    
    val config = WayebConfig(
      task = "learnSPST",
      fsmModel = fsm.FSMModel.DSFA,
      streamFile = datasetPath,
      outputSpst = outputModelPath,
      patterns = patternPath,
      pMin = pMin,
      gammaMin = gamma,
      alpha = alpha,
      r = r,
      domainSpecificStream = "json"
    )
    
    BeepBeep.runLearnSPST(config)
  }

  // Deprecated simplistic version
  def train(config: WayebConfig): Unit = {
    BeepBeep.runLearnSPST(config)
  }
}
