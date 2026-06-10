package chess.bot.engine

import zio.*

import chess.bot.engine.nnue.NnueEvaluator
import chess.codec.FenParserRegex
import chess.model.board.GameState

/** Throwaway wall-clock benchmark: time-to-fixed-depth with the
  * incremental NNUE accumulator ON vs OFF (full rebuild). Same search,
  * same result (see IncrementalSearchSpec) — the ratio is the eval-speed
  * win. Run: `sbt 'botEngine/Test/runMain chess.bot.engine.IncrementalBench'` */
object IncrementalBench:

  def main(args: Array[String]): Unit =
    val rt   = Runtime.default
    val nnue = NnueEvaluator.loadResource("/nnue-v1.bin").get
    val eval = new HybridEvaluator(Evaluator.materialOnly, nnue, 0.3)

    def parse(fen: String): GameState =
      Unsafe.unsafe(implicit u => rt.unsafe.run(FenParserRegex.parse(fen)).getOrThrow())
    def mk(inc: Boolean): Search =
      new AlphaBetaSearch(
        eval = eval, tt = TranspositionTable.inMemory(1 << 20),
        book = OpeningBook.Empty, incrementalAccumulators = inc,
      )
    def run(s: Search, st: GameState, depth: Int): Unit =
      Unsafe.unsafe(implicit u => rt.unsafe.run(s.bestMove(st, depth)).getOrThrow()): Unit

    val state = parse("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")

    // JIT warmup
    for _ <- 1 to 3 do { run(mk(false), state, 6); run(mk(true), state, 6) }

    def timeMs(inc: Boolean, depth: Int): Long =
      val s  = mk(inc) // fresh TT → cold search, fair comparison
      val t0 = java.lang.System.nanoTime()
      run(s, state, depth)
      (java.lang.System.nanoTime() - t0) / 1_000_000L

    for d <- Seq(7, 8) do
      val full = timeMs(inc = false, d)
      val incr = timeMs(inc = true, d)
      println(f"[bench] depth $d: full-rebuild=${full}ms  incremental=${incr}ms  speedup=${full.toDouble / math.max(1, incr)}%.2fx")
