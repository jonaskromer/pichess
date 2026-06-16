package chess.bot.engine

import zio.test.*

import chess.bot.engine.nnue.{NnueAccumulator, NnueEvaluator}
import chess.codec.FenParserRegex
import chess.model.board.PositionView

object CachedEvaluatorSpec extends ZIOSpecDefault:

  private val nnue = NnueEvaluator.loadResource("/nnue-v1.bin").get

  /** Decorator that counts inner calls and exposes an NNUE, so we can verify
    * the cache (a) preserves `incrementalNet` and (b) caches the maintained-
    * accumulator path. */
  private final class Counting extends Evaluator:
    var evalCalls     = 0
    var evalWithCalls = 0
    def evaluate(state: PositionView): Int = { evalCalls += 1; 17 }
    override def incrementalNet: Option[NnueEvaluator] = Some(nnue)
    override def evaluateWith(acc: NnueAccumulator, state: PositionView): Int =
      evalWithCalls += 1
      17

  private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  def spec = suite("CachedEvaluator + incremental")(
    test("passes incrementalNet through (wrapping in the cache must NOT disable incremental)") {
      assertTrue(CachedEvaluator.of(new Counting).incrementalNet.isDefined)
    },
    test("caches the evaluateWith path — a transposition hits the cache (one inner call)") {
      val inner = new Counting
      val c     = CachedEvaluator.of(inner)
      for state <- FenParserRegex.parse(startFen)
      yield
        val acc = nnue.freshAccumulator()
        val a   = c.evaluateWith(acc, state)
        val b   = c.evaluateWith(acc, state) // same position → served from cache
        assertTrue(a == 17, b == 17, inner.evalWithCalls == 1)
    },
    test("evaluate and evaluateWith share one cache (same Zobrist key)") {
      val inner = new Counting
      val c     = CachedEvaluator.of(inner)
      for state <- FenParserRegex.parse(startFen)
      yield
        val acc = nnue.freshAccumulator()
        c.evaluate(state)          // populates the cache
        c.evaluateWith(acc, state) // hits it → inner.evaluateWith not called
        assertTrue(inner.evalCalls == 1, inner.evalWithCalls == 0)
    },
  )
