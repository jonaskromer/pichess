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
    test("stays within ~1.5x budget in a low-branching endgame (mid-iteration abort)") {
      // Regression: budgeted ID had no mid-iteration deadline. In a
      // low-branching position the cheap early iterations fool the
      // `4 x lastIter` projection into starting an ever-deeper iteration
      // that then runs to completion unbounded — observed as multi-second
      // moves (a flag-on-time risk live). negamax now aborts the in-flight
      // iteration at the 1.5x-budget deadline. Uses the expensive baked
      // NNUE eval (production-like per-node cost) so a runaway deep
      // iteration would actually overrun. Generous bound: a real overrun
      // is seconds; the fix keeps it near 1.5x budget + node-check slack.
      val search =
        Search.alphaBeta(chess.bot.engine.nnue.NnueEvaluator.loadResource("/nnue-v1.bin").get)
      for
        state <- FenParserRegex.parse("8/6p1/5k2/8/8/2K5/1P6/8 w - - 0 1")
        startNs = java.lang.System.nanoTime()
        move   <- search.bestMoveWithBudget(state, budgetMillis = 100)
        elapsedMs = (java.lang.System.nanoTime() - startNs) / 1_000_000L
      yield assertTrue(move.isDefined, elapsedMs < 900)
    },
    test("returns None at a checkmate position") {
      val search = Search.alphaBeta(Evaluator.materialOnly)
      for
        state <- FenParserRegex.parse("6Qk/6PK/8/8/8/8/8/8 b - - 0 1")
        move  <- search.bestMoveWithBudget(state, budgetMillis = 50)
      yield assertTrue(move.isEmpty)
    },
    test("returns a legal move even when the TT holds a stale mate score for the root") {
      // Regression: budgetedBestMove's mate / out-of-budget early-exit
      // used to fire on the very FIRST iteration by reading a stale TT
      // mate score for the root — returning None (no iteration had run
      // yet) at a perfectly legal position. That made the bot freeze /
      // concede exactly when it was losing (a mate score sits in the TT
      // then). It must still complete depth 1 and return a move.
      val tt     = TranspositionTable.inMemory(maxEntries = 32)
      val search = Search.alphaBetaWith(Evaluator.materialOnly, tt)
      for
        state <- FenParserRegex.parse(
                   "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                 )
        _ = tt.put(
              chess.model.rules.Zobrist.hash(state),
              TranspositionTable.Entry(
                depth = 99,
                score = -100_000, // "we are being mated" — triggers mateFound
                kind = TranspositionTable.Kind.Exact,
                bestMove = None,
              ),
            )
        move <- search.bestMoveWithBudget(state, budgetMillis = 50)
      yield assertTrue(move.isDefined)
    },
  )
