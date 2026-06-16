package chess.bot.engine

import zio.*
import zio.test.*
import zio.test.Assertion.equalTo

import chess.codec.FenParserRegex
import chess.bot.engine.internal.{RulesAdapter, SearchPos}
import chess.model.board.GameState

/** Perft (move-path enumeration) — the gold-standard correctness check for
  * move generation + apply + legality. Counts the leaf nodes of the legal
  * move tree to a fixed depth and compares against the published reference
  * values (Chess Programming Wiki). One test exercises every special move
  * at once: castling, en passant, promotion INCLUDING under-promotions,
  * pins, and check evasion — things a positional spec can miss.
  *
  * Established here against the CURRENT (immutable) apply so it freezes a
  * trustworthy baseline; the copy-make refactor must reproduce these exact
  * counts (the perft harness will be re-pointed at the copy-make apply, and
  * the reference values are the equivalence proof). */
object PerftSpec extends ZIOSpecDefault:

  /** Count leaves of the legal move tree at `depth`, via the search's own
    * generator ([[RulesAdapter.fillCapturesAndQuiets]], under-promotions ON)
    * and the COPY-MAKE apply ([[SearchPos.copyMakeInto]] — returns `false`
    * when the move left the king in check, i.e. illegal, so it's skipped).
    * Re-pointed from the immutable `applyMoveInt` to copy-make: reproducing
    * the published counts is the equivalence proof. Per-ply `SearchPos` +
    * move buffers are indexed by remaining depth (one per recursion level),
    * so a child's generation/apply can't clobber the list its parent is
    * still iterating. */
  private def perft(state: GameState, depth: Int): Long =
    val positions = Array.fill(depth + 1)(new SearchPos)
    val capBufs   = Array.fill(depth + 1)(new Array[Int](256))
    val quietBufs = Array.fill(depth + 1)(new Array[Int](256))
    positions(depth).setFrom(state)
    def rec(pos: SearchPos, d: Int): Long =
      if d == 0 then 1L
      else
        val cap        = capBufs(d)
        val quiet      = quietBufs(d)
        val packed     = RulesAdapter.fillCapturesAndQuiets(pos, cap, quiet, underPromotion = true)
        val capCount   = (packed >>> 32).toInt
        val quietCount = packed.toInt
        val child      = positions(d - 1)
        var nodes = 0L
        var i = 0
        while i < capCount do
          if pos.copyMakeInto(child, cap(i)) then nodes += rec(child, d - 1)
          i += 1
        i = 0
        while i < quietCount do
          if pos.copyMakeInto(child, quiet(i)) then nodes += rec(child, d - 1)
          i += 1
        nodes
    rec(positions(depth), depth)

  private def perftTest(name: String, fen: String, expected: (Int, Long)*) =
    test(name) {
      for state <- FenParserRegex.parse(fen)
      yield
        val actual = expected.map((d, _) => d -> perft(state, d)).toList
        assert(actual)(equalTo(expected.toList))
    }

  def spec = suite("Perft (move-gen + apply + legality)")(
    perftTest(
      "startposition",
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      1 -> 20L, 2 -> 400L, 3 -> 8902L, 4 -> 197281L,
    ),
    perftTest(
      "Kiwipete (castling, EP, many captures)",
      "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
      1 -> 48L, 2 -> 2039L, 3 -> 97862L,
    ),
    perftTest(
      "position 3 (EP discoveries + checks)",
      "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
      1 -> 14L, 2 -> 191L, 3 -> 2812L, 4 -> 43238L,
    ),
    perftTest(
      "position 4 (promotions + pins)",
      "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
      1 -> 6L, 2 -> 264L, 3 -> 9467L,
    ),
    perftTest(
      "position 5 (under-promotion + cramped)",
      "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
      1 -> 44L, 2 -> 1486L, 3 -> 62379L,
    ),
  )
