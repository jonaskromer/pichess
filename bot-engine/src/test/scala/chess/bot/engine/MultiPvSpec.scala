package chess.bot.engine

import zio.test.*

import chess.codec.FenParserRegex

/** Multi-PV exercises [[Search.bestMoves]] — returns the top-K root moves with
  * their scores, sorted descending.
  */
object MultiPvSpec extends ZIOSpecDefault:

  def spec = suite("Multi-PV (Search.bestMoves)")(
    test("returns up to k moves at the starting position") {
      val search = Search.alphaBeta(Evaluator.materialOnly)
      for
        state <- FenParserRegex.parse(
          "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        )
        top3 <- search.bestMoves(state, depth = 2, k = 3)
      yield assertTrue(
        top3.size == 3,
        // scores are descending
        top3.zip(top3.drop(1)).forall { case ((_, a), (_, b)) => a >= b }
      )
    },
    test("returns an empty list at a checkmate position") {
      val search = Search.alphaBeta(Evaluator.materialOnly)
      for
        state <- FenParserRegex.parse("6Qk/6PK/8/8/8/8/8/8 b - - 0 1")
        top <- search.bestMoves(state, depth = 2, k = 3)
      yield assertTrue(top.isEmpty)
    },
    test("PV[0] matches bestMove") {
      val search = Search.alphaBeta(Evaluator.materialOnly)
      for
        state <- FenParserRegex.parse("q7/8/8/8/8/8/8/R6K w - - 0 1")
        best <- search.bestMove(state, depth = 2)
        top <- search.bestMoves(state, depth = 2, k = 5)
      yield assertTrue(
        best.isDefined,
        top.headOption.map(_._1).contains(best.get)
      )
    }
  )
