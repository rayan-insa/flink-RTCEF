package observer.model

import org.scalatest.FunSuite

class InstructionSpec extends FunSuite {

  test("Instruction.retrain should create retrain instruction") {
    val instr = Instruction.retrain("test trigger")
    assert(instr.instructionType === RetrainInstruction)
    assert(instr.triggeredBy === "test trigger")
  }

  test("Instruction.optimize should create optimize instruction") {
    val instr = Instruction.optimize("test trigger")
    assert(instr.instructionType === OptimizeInstruction)
    assert(instr.triggeredBy === "test trigger")
  }

  test("Instruction should serialize to JSON correctly") {
    val instr = Instruction(RetrainInstruction, 1234567890L, "slope_condition")
    val json = instr.toJson
    
    assert(json.contains("\"type\":\"retrain\""))
    assert(json.contains("\"timestamp\":1234567890"))
    assert(json.contains("\"triggeredBy\":\"slope_condition\""))
  }

  test("RetrainInstruction should have correct value") {
    assert(RetrainInstruction.value === "retrain")
  }

  test("OptimizeInstruction should have correct value") {
    assert(OptimizeInstruction.value === "optimize")
  }
}
