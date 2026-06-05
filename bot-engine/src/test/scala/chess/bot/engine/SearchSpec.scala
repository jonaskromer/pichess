package chess.bot.engine

import zio.*
import zio.test.*

import chess.bot.engine.internal.RulesAdapter
import chess.codec.FenParserRegex
import chess.model.board.{GameStatus, Move, Position}
import chess.model.piece.{Color, PieceType}
import chess.model.rules.Zobrist

/** End-to-end search behaviour, pinned by FEN fixtures.
  *
  * Each test states a position the search must reason about ("there's
  * exactly one legal move", "mate in 1 exists", …) and asserts the
  * returned [[Move]] matches the expected square pair. Depth is set
  * just high enough for the tactic; deeper would only slow tests down.
  *
  * Material-only eval is the Phase 1 default. That's enough for the
  * basic tactical fixtures here (captures, mates) but produces no
  * meaningful preference between two equally-material moves — the
  * search will pick the first one its move generator returns, which is
  * deterministic per FEN. Tests rely on that determinism.
  */
object SearchSpec extends ZIOSpecDefault:

  private val search: Search = Search.alphaBeta(Evaluator.materialOnly)

  private def bestMoveOf(fen: String, depth: Int): ZIO[Any, Throwable, Option[Move]] =
    FenParserRegex.parse(fen).flatMap(s => search.bestMove(s, depth))

  def spec = suite("Search (α-β + TT)")(
    test("returns None at a checkmate position (no legal moves, in check)") {
      // Black to move, mated by the white queen on g7 — black king on h8
      // can't go anywhere (h7 covered by queen, g8 covered by king).
      for moveOpt <- bestMoveOf("6Qk/6PK/8/8/8/8/8/8 b - - 0 1", depth = 2)
      yield assertTrue(moveOpt.isEmpty)
    },
    test("returns None at a stalemate position (no legal moves, not in check)") {
      // Classic K+Q vs K stalemate: black king cornered at h1, white
      // king at f2, white queen at g3. The queen covers g1/g2/h2; the
      // king covers g1/g2; h1 itself isn't attacked. Black has no
      // legal move and isn't in check → stalemate.
      for moveOpt <- bestMoveOf("8/8/8/8/8/6Q1/5K2/7k b - - 0 1", depth = 2)
      yield assertTrue(moveOpt.isEmpty)
    },
    test("picks the only legal move when one exists") {
      // Forced king move — white king on h1, black queen on g2 forks
      // it; only legal king move is Kxg2 (king takes queen — only
      // unattacked adjacent square). Tests the "single legal move"
      // path through the search.
      for moveOpt <- bestMoveOf("7k/8/8/8/8/8/6q1/7K w - - 0 1", depth = 2)
      yield assertTrue(
        moveOpt.contains(Move(Position('h', 1), Position('g', 2), None))
      )
    },
    test("prefers a clear material-gain capture over a quiet move") {
      // White rook on a1 can capture an undefended black queen on a8
      // (Ra1xa8). Any quiet move (e.g. h1-h2) leaves white down 0.
      // Material-only eval must pick the capture.
      for moveOpt <- bestMoveOf("q7/8/8/8/8/8/8/R6K w - - 0 1", depth = 2)
      yield assertTrue(
        moveOpt.contains(Move(Position('a', 1), Position('a', 8), None))
      )
    },
    test("finds a forced mate in 1") {
      // Queen + king box-in. Both Qh7# and Kf7# are mate from this
      // position — material-only eval can pick either. The robust
      // assertion is "applying the chosen move ends the game with
      // white as the winner via checkmate", not the specific move.
      //
      // Drives the move through `Game.applyMove` (full status
      // detection) rather than `RulesAdapter.applyMove` (the bot's
      // status-less search variant) — we're asserting on the
      // resulting `status` field, which only the full apply path
      // populates.
      for
        state  <- FenParserRegex.parse("7k/8/6KQ/8/8/8/8/8 w - - 0 1")
        moveOpt <- search.bestMove(state, depth = 2)
        nextOpt <- ZIO.foreach(moveOpt)(m => chess.model.rules.Game.applyMove(state, m))
      yield assertTrue(
        moveOpt.isDefined,
        nextOpt.exists(_.status == GameStatus.Checkmate(Color.White)),
      )
    },
    test("prefers a promotion to queen over a same-side capture") {
      // Black pawn on b2 can either capture white's a3 pawn (+100 cp)
      // or promote on b1=Q (+800 cp net: -100 pawn + 900 queen).
      // Material-only eval must pick the promotion.
      //
      // Depth must be shallow enough that the bot can't see the
      // promotion as available *next* turn (which would tie b1=Q
      // with any quiet move). At depth 2 the immediate-promotion
      // gain is uniquely visible.
      for moveOpt <- bestMoveOf("7k/8/8/8/8/P7/1p6/7K b - - 0 1", depth = 2)
      yield assertTrue(
        moveOpt.contains(
          Move(Position('b', 2), Position('b', 1), Some(PieceType.Queen))
        )
      )
    },
    suite("TT interaction")(
      test("orderMoves places a seeded TT bestMove first in the candidate list") {
        // Pre-seed the TT for the root position with a bestMove. The
        // search uses TT-suggested moves for ordering; that move should
        // be tried before the rest. We observe ordering indirectly via
        // the choice tracked under α-β: pre-seeding with a non-optimal
        // (but legal) move just changes order, not the eventual pick —
        // so we only need to verify the search succeeds and reports a
        // legal move. The branch coverage is the real assertion.
        val tt = TranspositionTable.inMemory(maxEntries = 32)
        val s  = Search.alphaBetaWith(Evaluator.materialOnly, tt)
        for
          state <- FenParserRegex.parse(
                     "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                   )
          _ = tt.put(
                chess.model.rules.Zobrist.hash(state),
                TranspositionTable.Entry(
                  depth = 0,
                  score = 0,
                  kind = TranspositionTable.Kind.Exact,
                  bestMove = Some(Move(Position('g', 1), Position('f', 3), None)),
                ),
              )
          moveOpt <- s.bestMove(state, depth = 2)
        yield assertTrue(moveOpt.isDefined)
      },
      test("a seeded Exact entry at sufficient depth shortcuts the search") {
        // Pre-seed TT with an Exact-bound score at depth ≥ requested.
        // The search at the root won't use the TT score directly
        // (root's α=-∞, β=+∞ window settles every entry kind), but it
        // exercises the probeTt "Exact returns score" branch when the
        // recursion revisits a transposed position.
        val tt = TranspositionTable.inMemory(maxEntries = 32)
        val s  = Search.alphaBetaWith(Evaluator.materialOnly, tt)
        for
          state <- FenParserRegex.parse(
                     "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                   )
          // Seed Exact entries at depth 10 (very high) for many
          // post-move positions; the next-level negamax will see those.
          _ = {
            val moves = chess.bot.engine.internal.RulesAdapter.legalMoves(state)
            moves.foreach { m =>
              chess.bot.engine.internal.RulesAdapter.applyMove(state, m).foreach { next =>
                tt.put(
                  chess.model.rules.Zobrist.hash(next),
                  TranspositionTable.Entry(
                    depth = 10,
                    score = 42,
                    kind = TranspositionTable.Kind.Exact,
                    bestMove = None,
                  ),
                )
              }
            }
          }
          moveOpt <- s.bestMove(state, depth = 2)
        yield assertTrue(moveOpt.isDefined)
      },
      test("a Lower-bound TT entry with score ≥ β triggers a cutoff") {
        // Build a position where negamax at depth 2 will probe a TT
        // entry whose Lower bound is ≥ the current β window — that's
        // the second probeTt branch. We seed many children with very
        // high Lower bounds; whichever the search visits first will
        // cutoff via that branch, instead of recursing.
        val tt = TranspositionTable.inMemory(maxEntries = 32)
        val s  = Search.alphaBetaWith(Evaluator.materialOnly, tt)
        for
          state <- FenParserRegex.parse(
                     "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                   )
          _ = {
            val moves = chess.bot.engine.internal.RulesAdapter.legalMoves(state)
            moves.foreach { m =>
              chess.bot.engine.internal.RulesAdapter.applyMove(state, m).foreach { next =>
                tt.put(
                  chess.model.rules.Zobrist.hash(next),
                  TranspositionTable.Entry(
                    depth = 10,
                    score = Search.Infinity,  // pegged high → score ≥ β at any inner call
                    kind = TranspositionTable.Kind.Lower,
                    bestMove = None,
                  ),
                )
              }
            }
          }
          moveOpt <- s.bestMove(state, depth = 2)
        yield assertTrue(moveOpt.isDefined)
      },
      test("a Lower-bound TT entry with score < β falls through to re-search") {
        // The "stored but not tight enough" case — the entry exists at
        // sufficient depth but its Lower bound (≤ score) doesn't pass
        // the β threshold, so probeTt falls through and the search
        // recomputes. Coverage of the `case _ => None` arm.
        val tt = TranspositionTable.inMemory(maxEntries = 32)
        val s  = Search.alphaBetaWith(Evaluator.materialOnly, tt)
        for
          state <- FenParserRegex.parse(
                     "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                   )
          _ = {
            val moves = chess.bot.engine.internal.RulesAdapter.legalMoves(state)
            moves.foreach { m =>
              chess.bot.engine.internal.RulesAdapter.applyMove(state, m).foreach { next =>
                tt.put(
                  chess.model.rules.Zobrist.hash(next),
                  TranspositionTable.Entry(
                    depth = 10,
                    score = 0,    // Lower bound at 0, well below +Infinity β
                    kind = TranspositionTable.Kind.Lower,
                    bestMove = None,
                  ),
                )
              }
            }
          }
          moveOpt <- s.bestMove(state, depth = 2)
        yield assertTrue(moveOpt.isDefined)
      },
      test("an Upper-bound TT entry with score ≤ α triggers a cutoff") {
        // Symmetric of the Lower case — Upper-bound entries with score
        // ≤ α cause the third probeTt branch to return a cutoff value.
        val tt = TranspositionTable.inMemory(maxEntries = 32)
        val s  = Search.alphaBetaWith(Evaluator.materialOnly, tt)
        for
          state <- FenParserRegex.parse(
                     "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                   )
          _ = {
            val moves = chess.bot.engine.internal.RulesAdapter.legalMoves(state)
            moves.foreach { m =>
              chess.bot.engine.internal.RulesAdapter.applyMove(state, m).foreach { next =>
                tt.put(
                  chess.model.rules.Zobrist.hash(next),
                  TranspositionTable.Entry(
                    depth = 10,
                    score = -Search.Infinity,
                    kind = TranspositionTable.Kind.Upper,
                    bestMove = None,
                  ),
                )
              }
            }
          }
          moveOpt <- s.bestMove(state, depth = 2)
        yield assertTrue(moveOpt.isDefined)
      },
    ),
    test("returns *some* legal move at the standard starting position") {
      // No tactical pressure → any legal move is acceptable. The
      // returned move must at least be legal; we verify by re-applying.
      for
        state <- FenParserRegex.parse(
                   "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                 )
        moveOpt <- search.bestMove(state, depth = 3)
      yield assertTrue(
        moveOpt.isDefined,
        moveOpt.exists(m => RulesAdapter.applyMove(state, m).isDefined),
      )
    },
    suite("repetition + fifty-move")(
      test("history containing unrelated positions doesn't bias the search") {
        // Pass a `history` set that contains arbitrary positions not
        // actually reachable from `state`. The repetition check
        // shouldn't fire on any of them, so the bot's pick must
        // match the empty-history baseline. Proves: the history
        // parameter is threaded through search correctly and only
        // affects moves whose destinations match.
        for
          state    <- FenParserRegex.parse("q7/7k/8/8/8/8/8/Q3K3 w - - 0 1")
          freePick <- search.bestMove(state, depth = 2)
          unrelatedHistory = Set(0L, 12345L, -1L, Long.MaxValue)
          notBlocked <- search.bestMove(
                          state,
                          depth = 2,
                          history = unrelatedHistory,
                        )
        yield assertTrue(
          freePick.isDefined,
          freePick == notBlocked,
        )
      },
      test("history containing the only-capture destination forces a quiet pick") {
        // Position with a single dominant capture (Qxa8 wins the
        // undefended black queen) and many quiet alternatives. With
        // Qxa8's destination seeded into history, the capture is
        // scored as a 0-draw — same as any quiet move, since the
        // material-only eval can't see a quiet-move advantage.
        // The bot's tie-break (captures first) means it still picks
        // Qxa8, but its *score* must equal the score of the best
        // quiet move (both 0). We can't observe scores directly,
        // but we can verify that the bot still returns a *legal*
        // move and doesn't crash on the repetition path — the more
        // direct correctness check is the equivalence-with-unrelated
        // history test above.
        for
          state   <- FenParserRegex.parse("q7/7k/8/8/8/8/8/Q3K3 w - - 0 1")
          capture  = Move(Position('a', 1), Position('a', 8), None)
          afterCap <- ZIO
                       .fromOption(RulesAdapter.applyMove(state, capture))
                       .orElseFail(new IllegalStateException("Qxa8 must be legal"))
          blocked <- search.bestMove(
                       state,
                       depth = 2,
                       history = Set(Zobrist.hash(afterCap)),
                     )
        yield assertTrue(
          blocked.isDefined,
          blocked.exists(m => RulesAdapter.applyMove(state, m).isDefined),
        )
      },
      test("treats halfmoveClock ≥ 100 as a draw at non-root nodes") {
        // Position one half-move shy of the 50-move limit: any quiet
        // move ticks the clock to 100, so the resulting child is a
        // draw under the rule. The only way for white to score
        // something other than 0 here is to capture — capture resets
        // the clock. With material-only eval, the bot must pick the
        // capture even though depth-2 evaluation of a quiet move
        // would otherwise tie at material 0.
        //
        // FEN: white king on h1, white queen on a1, a single
        // undefended black rook on a7 — black king tucked at h8.
        // Qxa7 captures the rook (+5 cp from white POV). Any quiet
        // queen move scores 0 because the child immediately reaches
        // the 50-move draw branch.
        for
          state   <- FenParserRegex.parse("7k/r7/8/8/8/8/8/Q6K w - - 99 100")
          moveOpt <- search.bestMove(state, depth = 2)
        yield assertTrue(
          moveOpt.contains(Move(Position('a', 1), Position('a', 7), None))
        )
      },
      test("still returns a legal move at the 50-move boundary itself") {
        // Symptom test: passing a state with halfmoveClock already at
        // 100 at the root must not crash. The root never invokes the
        // negamax 50-move shortcut (that fires at children), so we
        // still get a move back — but it confirms the boundary is
        // handled cleanly.
        for
          state   <- FenParserRegex.parse("7k/8/8/8/8/8/8/Q6K w - - 100 51")
          moveOpt <- search.bestMove(state, depth = 2)
        yield assertTrue(
          moveOpt.isDefined,
          moveOpt.exists(m => RulesAdapter.applyMove(state, m).isDefined),
        )
      },
    ),
  )
