package chess.bot.engine

import zio.*
import zio.test.*

import chess.bot.engine.nnue.NnueEvaluator
import chess.codec.FenParserRegex

/** End-to-end correctness gate for the incremental NNUE accumulator: a search
  * that maintains the accumulator across make/unmake must return
  * **byte-identical** results — same bestMove AND same score, at the same depth
  * — as a search that rebuilds the accumulator from scratch each leaf. Any
  * wiring bug (a make-move site that doesn't advance/restore the accumulator)
  * desyncs it from the board, the leaf evals drift, and the search picks a
  * different move / reports a different score — caught here. Exercised across
  * quiet, tactical (captures/checks → quiescence) and endgame positions so the
  * wrapped paths (searchMoves stages, LMR re-search, quiescence, null move) all
  * fire.
  */
object IncrementalSearchSpec extends ZIOSpecDefault:

  private val nnue = NnueEvaluator.loadResource("/nnue-v1.bin").get

  private def mk(eval: Evaluator, incremental: Boolean): Search =
    new AlphaBetaSearch(
      eval = eval,
      tt = TranspositionTable.inMemory(1 << 16),
      book = OpeningBook.Empty,
      incrementalAccumulators = incremental
    )

  private val fens = List(
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", // start
    "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3", // open game
    "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", // kiwipete (very tactical)
    "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1" // rook endgame
  )

  private def matchesFullRebuild(
      eval: Evaluator
  ): ZIO[Any, Throwable, TestResult] =
    val inc = mk(eval, incremental = true)
    val full = mk(eval, incremental = false)
    ZIO
      .foreach(fens) { fen =>
        for
          state <- FenParserRegex
            .parse(fen)
            .mapError(e => new RuntimeException(e.toString))
          moveInc <- inc.bestMove(state, depth = 5)
          moveFull <- full.bestMove(state, depth = 5)
          scoreInc <- inc.evaluate(state, depth = 5)
          scoreFul <- full.evaluate(state, depth = 5)
        yield assertTrue(moveInc == moveFull, scoreInc == scoreFul) ?? fen
      }
      .map(_.reduce(_ && _))

  def spec = suite("incremental NNUE search == from-scratch rebuild")(
    test("pure NNUE eval")(matchesFullRebuild(nnue)),
    test("HCE+NNUE hybrid eval")(
      matchesFullRebuild(new HybridEvaluator(Evaluator.materialOnly, nnue, 0.3))
    )
  )
