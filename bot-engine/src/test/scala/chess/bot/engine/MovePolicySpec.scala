package chess.bot.engine

import zio.test.*

object MovePolicySpec extends ZIOSpecDefault:

  // `chooseNoisy` is generic in the move type, so we stand in cheap String
  // "moves" to exercise the selection logic without building real `Move`s.
  private val ranked: List[(String, Int)] =
    List("best" -> 30, "second" -> 20, "third" -> 10, "fourth" -> 5)

  def spec = suite("MovePolicy.chooseNoisy")(
    test("empty candidate list yields no move") {
      assertTrue(
        MovePolicy.chooseNoisy(List.empty[(String, Int)], 0.5, 0.0, 0.0).isEmpty
      )
    },
    test("no noise always plays the best move, whatever the choice roll") {
      assertTrue(
        MovePolicy.chooseNoisy(ranked, 0.0, 0.0, 0.99) == Some("best"),
        MovePolicy.chooseNoisy(ranked, 0.0, 0.5, 0.10) == Some("best")
      )
    },
    test("a blunder roll at/above the threshold keeps the best move") {
      // noise 0.3, blunderRoll 0.3 (== threshold, not below) ⇒ best.
      assertTrue(MovePolicy.chooseNoisy(ranked, 0.3, 0.3, 0.9) == Some("best"))
    },
    test("a blunder roll below the threshold samples the top-K") {
      assertTrue(
        MovePolicy.chooseNoisy(ranked, 1.0, 0.0, 0.0)  == Some("best"),   // idx 0
        MovePolicy.chooseNoisy(ranked, 1.0, 0.0, 0.34) == Some("second"), // idx 1
        MovePolicy.chooseNoisy(ranked, 1.0, 0.0, 0.67) == Some("third"),  // idx 2
        // only the top-3 are ever in play — the 4th-best is never chosen
        MovePolicy.chooseNoisy(ranked, 1.0, 0.0, 0.99) == Some("third")
      )
    },
    test("a choice roll of exactly 1.0 stays in bounds") {
      assertTrue(MovePolicy.chooseNoisy(ranked, 1.0, 0.0, 1.0) == Some("third"))
    },
    test("fewer candidates than the top-K clamps to what's available") {
      val two = List("best" -> 10, "second" -> 5)
      assertTrue(MovePolicy.chooseNoisy(two, 1.0, 0.0, 0.99) == Some("second"))
    }
  )
