package observer.model

/**
  * Instruction types for model adaptation
  */
sealed trait InstructionType {
  def value: String
}

case object RetrainInstruction extends InstructionType {
  override def value: String = "retrain"
}

case object OptimizeInstruction extends InstructionType {
  override def value: String = "optimize"
}

/**
  * Represents an instruction to be sent to the Factory service
  *
  * @param instructionType Type of instruction (retrain or optimize)
  * @param timestamp Timestamp when the instruction was generated
  * @param triggeredBy Description of what triggered the instruction
  */
case class Instruction(
  instructionType: InstructionType,
  timestamp: Long = System.currentTimeMillis(),
  triggeredBy: String = ""
) {
  def toJson: String = {
    s"""{"type":"${instructionType.value}","timestamp":$timestamp,"triggeredBy":"$triggeredBy"}"""
  }

  override def toString: String = toJson
}

object Instruction {
  def retrain(triggeredBy: String = ""): Instruction = 
    Instruction(RetrainInstruction, triggeredBy = triggeredBy)
  
  def optimize(triggeredBy: String = ""): Instruction = 
    Instruction(OptimizeInstruction, triggeredBy = triggeredBy)
}
