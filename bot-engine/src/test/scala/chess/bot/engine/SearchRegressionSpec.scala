package chess.bot.engine

import zio.*
import zio.test.*

import chess.codec.FenParserRegex
import chess.model.board.{Move, Position}
import chess.model.rules.Zobrist

/** Regression guards for two planned allocation-reduction refactors of the
  * search hot path:
  *
  *   - #2 — replace the per-node immutable `Set[Long]` repetition history with
  *     a mutable ply-indexed `long[]` path stack. Must preserve draw-by-
  *     repetition detection exactly.
  *   - #3 — convert the `applyMoveInt(...).foreach { … }` move loops to
  *     `match`. Must not change which moves are searched or the result.
  *
  * Both are meant to be behaviour-preserving, so these pin the CURRENT search
  * output: write them green now, and they go red if either refactor changes
  * repetition handling or the chosen move. Uses the material-only evaluator so
  * results are deterministic and eval-independent (the refactors are in the
  * search, not the eval). */
object SearchRegressionSpec extends ZIOSpecDefault:

  private val search: Search = Search.alphaBeta(Evaluator.materialOnly)

  private def mv(from: String, to: String): Move =
    Move(Position(from(0), from(1).asDigit), Position(to(0), to(1).asDigit), None)

  def spec = suite("SearchRegressionSpec")(
    // ── #2: repetition via `history` ───────────────────────────────────
    test("a move into a position present in `history` is scored as a draw") {
      // White (Kh1, Ra1) is down a rook vs Black (Kh8, Rb8, Rg8) — with
      // material eval every move keeps White losing, EXCEPT Ra1-a2, whose
      // resulting position is placed in `history`, making it an immediate
      // repetition (draw = 0). A working repetition check must pick Ra1-a2.
      for
        root  <- FenParserRegex.parse("1r4rk/8/8/8/8/8/8/R6K w - - 0 1")
        after <- FenParserRegex.parse("1r4rk/8/8/8/8/8/R7/7K b - - 1 1")
        hp     = Zobrist.hash(after)
        chosen <- search.bestMove(root, depth = 4, history = Set(hp))
      yield assertTrue(chosen.contains(mv("a1", "a2")))
    },
    // A benign history (a hash never reached in the tree) must NOT change the
    // chosen move vs an empty history — i.e. history is consulted, not blindly
    // applied. Guards against the path-stack scanning the wrong range.
    test("an unreachable history entry does not change the chosen move") {
      for
        root    <- FenParserRegex.parse("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 1")
        baseline <- search.bestMove(root, depth = 4)
        withJunk <- search.bestMove(root, depth = 4, history = Set(0x1234abcdL))
      yield assertTrue(baseline == withJunk)
    },
    // ── #2 + #3: search-result determinism (golden) ────────────────────
    // Pins bestMove at fixed depth across diverse positions. The path-stack
    // (#2) and foreach→match move loops (#3) are behaviour-preserving, so
    // these must stay identical. Golden values recorded from the current code.
    test("bestMove is deterministic across diverse positions (move-loop + history guard)") {
      for
        start <- FenParserRegex.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        kiwi  <- FenParserRegex.parse("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        tactic <- FenParserRegex.parse("r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 1")
        mStart  <- search.bestMove(start, depth = 5)
        mKiwi   <- search.bestMove(kiwi, depth = 4)
        mTactic <- search.bestMove(tactic, depth = 5)
      yield assertTrue(
        mStart.contains(GOLDEN_START),
        mKiwi.contains(GOLDEN_KIWI),
        mTactic.contains(GOLDEN_TACTIC),
      )
    },
  )

  // Placeholders — filled from the first run (the assertTrue failure prints
  // the actual moves), then frozen as the regression baseline.
  private val GOLDEN_START:  Move = mv("a2", "a3")
  private val GOLDEN_KIWI:   Move = mv("e2", "a6")
  private val GOLDEN_TACTIC: Move = mv("d2", "d3")
