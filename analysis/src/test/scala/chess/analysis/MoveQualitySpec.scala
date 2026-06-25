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
    }
  )
