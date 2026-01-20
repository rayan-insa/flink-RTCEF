package observer.state

import org.scalatest.FunSuite

class ScoringWindowSpec extends FunSuite {

  test("ScoringWindow should add scores correctly") {
    val window = new ScoringWindow(5)
    window.update(0.5)
    window.update(0.6)
    window.update(0.7)
    
    assert(window.size === 3)
    assert(window.getScores === List(0.5, 0.6, 0.7))
  }

  test("ScoringWindow should maintain max size") {
    val window = new ScoringWindow(3)
    window.update(0.1)
    window.update(0.2)
    window.update(0.3)
    window.update(0.4)
    window.update(0.5)
    
    assert(window.size === 3)
    assert(window.getScores === List(0.3, 0.4, 0.5))
  }

  test("ScoringWindow should return latest score") {
    val window = new ScoringWindow(5)
    window.update(0.5)
    window.update(0.6)
    window.update(0.7)
    
    assert(window.getLatestScore === Some(0.7))
  }

  test("ScoringWindow should return None for latest score when empty") {
    val window = new ScoringWindow(5)
    assert(window.getLatestScore === None)
  }

  test("ScoringWindow should check if it has enough scores") {
    val window = new ScoringWindow(5)
    assert(!window.hasEnoughScores(3))
    
    window.update(0.5)
    window.update(0.6)
    window.update(0.7)
    
    assert(window.hasEnoughScores(3))
    assert(!window.hasEnoughScores(4))
  }

  test("ScoringWindow should clear all scores") {
    val window = new ScoringWindow(5)
    window.update(0.5)
    window.update(0.6)
    
    window.clear()
    
    assert(window.size === 0)
    assert(window.getScores === List())
  }
}
