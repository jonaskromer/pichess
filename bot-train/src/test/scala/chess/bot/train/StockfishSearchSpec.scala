package chess.bot.train

import zio.*
import zio.test.*

import chess.codec.FenParserRegex

/** Integration tests for the Stockfish subprocess adapter.
  *
  * Skipped when the `stockfish` binary isn't on PATH — local
  * dev / CI environments without it just see all suites as
  * "no tests ran" rather than a hard failure. To run these
  * locally: `brew install stockfish` (or set `STOCKFISH_BIN`).
  *
  * Doesn't cover Elo math or game outcomes — those are
  * Tournament's job. We just verify the UCI subprocess
  * round-trips correctly and produces a legal move at low depth. */
object StockfishSearchSpec extends ZIOSpecDefault:

  /** Probe for Stockfish at JVM start. If absent, the suite
    * collapses to a single passing "skipped" test so the project
    * gate stays green on machines without it. */
  private val stockfishAvailable: Boolean =
    try
      val pb = new ProcessBuilder(
        sys.env.getOrElse("STOCKFISH_BIN", "stockfish")
      )
      val p = pb.start()
      p.getOutputStream.write("quit\n".getBytes); p.getOutputStream.flush()
      p.waitFor()
      true
    catch case _: Throwable => false

  def spec = suite("StockfishSearch")(
    if !stockfishAvailable then
      test("Stockfish binary not on PATH — integration tests skipped") {
        assertTrue(true)
      }
    else
      test("spawns a stockfish subprocess and returns a legal move at depth 5") {
        ZIO.scoped {
          for
            engine <- StockfishSearch.spawn(skillLevel = Some(5), label = "test")
            state  <- FenParserRegex.parse(
                        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                      )
            move   <- engine.bestMove(state, depth = 5)
          yield assertTrue(
            move.isDefined,
            // Stockfish's first move from the starting position
            // is always a known opening move (e2e4 / d2d4 / Nf3 /
            // c2c4 etc). It must originate from rank 2 (a pawn
            // push) or rank 1 (a knight develop). Robust assert
            // that doesn't pin to a specific move.
            move.exists(m => m.from.row == 1 || m.from.row == 2),
          )
        }
      }
  )
