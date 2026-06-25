package chess.analysis

import zio.test.*

import chess.codec.Nag

object MoveQualitySpec extends ZIOSpecDefault:

  def spec = suite("MoveQuality")(
    test("classify by win-% drop, book, and only-move gap") {
      assertTrue(
        MoveQuality.classify(50.0, false, 0, isBook = true) == MoveClass.Book,
        MoveQuality.classify(25.0, false, 0, isBook = false) == MoveClass.Blunder,
        MoveQuality.classify(12.0, false, 0, isBook = false) == MoveClass.Mistake,
        MoveQuality.classify(6.0, false, 0, isBook = false) == MoveClass.Inaccuracy,
        MoveQuality.classify(1.0, true, 300, isBook = false) == MoveClass.Good, // only good move
        MoveQuality.classify(1.0, true, 10, isBook = false) == MoveClass.Best,  // best, alternatives fine
        MoveQuality.classify(1.0, false, 0, isBook = false) == MoveClass.Best   // near-best
      )
    },
    test("averageAccuracy: mean, or 100 when no moves") {
      assertTrue(
        MoveQuality.averageAccuracy(List(90.0, 100.0)) == 95.0,
        MoveQuality.averageAccuracy(Nil) == 100.0
      )
    },
    test("every MoveClass maps to its NAG (full vocabulary)") {
      val nags = MoveClass.values.map(_.nag).toList
      assertTrue(
        MoveClass.Book.nag == None,
        MoveClass.Best.nag == None,
        MoveClass.Good.nag == Some(Nag.Good),
        MoveClass.Brilliant.nag == Some(Nag.Brilliant),
        MoveClass.Interesting.nag == Some(Nag.Interesting),
        MoveClass.Inaccuracy.nag == Some(Nag.Dubious),
        MoveClass.Mistake.nag == Some(Nag.Mistake),
        MoveClass.Blunder.nag == Some(Nag.Blunder),
        nags.length == 8
      )
    },
    test("volatilityWeights: floor on a flat game, spikes at a swing") {
      // Flat win% → every weight at the floor; a 30-point jump → a big weight
      // on the moves whose window straddles it.
      val flat = MoveQuality.volatilityWeights(List(50.0, 50.0, 50.0))
      val swing = MoveQuality.volatilityWeights(List(50.0, 50.0, 80.0))
      assertTrue(
        flat == List(0.5, 0.5),
        swing.length == 2,
        swing.forall(_ >= 0.5),
        swing.max > 5.0
      )
    },
    test("weightedAccuracy: blend, empty→100, zero-weights→plain mean") {
      assertTrue(
        // Equal weights → weighted mean == plain mean == harmonic-blended here
        // only when all equal; with one low score the harmonic mean pulls down.
        MoveQuality.weightedAccuracy(Nil) == 100.0,
        // All-zero weights fall back to the unweighted mean (then blended with
        // the harmonic mean of the identical value → the value itself).
        MoveQuality.weightedAccuracy(List((80.0, 0.0))) == 80.0,
        // A blunder (low accuracy) at a high-volatility moment tanks the score
        // more than a flat average would.
        MoveQuality.weightedAccuracy(List((100.0, 0.5), (10.0, 8.0))) < 55.0
      )
    }
  )
