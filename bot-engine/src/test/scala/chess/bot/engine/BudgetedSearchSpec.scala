package chess.bot.engine

import zio.test.*
import zio.*

import chess.codec.FenParserRegex

/** Time-budgeted ID test: returns a legal move within the budget
  * and stays roughly inside the budgeted wall time. */
object BudgetedSearchSpec extends ZIOSpecDefault:

  def spec = suite("bestMoveWithBudget")(
    test("returns a legal move under a tight budget") {
      val search = Search.alphaBeta(Evaluator.materialOnly)
      for
        state <- FenParserRegex.parse(
                   "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                 )
        startNs = java.lang.System.nanoTime()
        move   <- search.bestMoveWithBudget(state, budgetMillis = 200)
        elapsedMs = (java.lang.System.nanoTime() - startNs) / 1_000_000L
      yield assertTrue(
        move.isDefined,
        // Hard cap is `1.5 × budget`, so allow 50% overrun then
        // some slack for JVM warmup on the first call.
        elapsedMs < 600,
      )
    },
    test("returns None at a checkmate position") {
      val search = Search.alphaBeta(Evaluator.materialOnly)
      for
        state <- FenParserRegex.parse("6Qk/6PK/8/8/8/8/8/8 b - - 0 1")
        move  <- search.bestMoveWithBudget(state, budgetMillis = 50)
      yield assertTrue(move.isEmpty)
    },
  )
