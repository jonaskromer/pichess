package chess.bot.engine

import zio.*
import zio.test.*

import chess.bot.engine.internal.RulesAdapter
import chess.codec.FenParserRegex
import chess.model.board.{GameStatus, Move, Position}
import chess.model.piece.{Color, PieceType}

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
      for
        state  <- FenParserRegex.parse("7k/8/6KQ/8/8/8/8/8 w - - 0 1")
        moveOpt <- search.bestMove(state, depth = 2)
        nextOpt = moveOpt.flatMap(m => RulesAdapter.applyMove(state, m))
      yield assertTrue(
        moveOpt.isDefined,
        nextOpt.exists(_.status == GameStatus.Checkmate(Color.White)),
      )
    },
    test("prefers a promotion to queen over a same-side capture") {
      // Black pawn on b2 can either capture white's a3 pawn (+100 cp)
      // or promote on b1=Q (+800 cp net: -100 pawn + 900 queen).
      // Material-only eval must pick the promotion.
      for moveOpt <- bestMoveOf("7k/8/8/8/8/P7/1p6/7K b - - 0 1", depth = 3)
      yield assertTrue(
        moveOpt.contains(
          Move(Position('b', 2), Position('b', 1), Some(PieceType.Queen))
        )
      )
    },
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
  )
