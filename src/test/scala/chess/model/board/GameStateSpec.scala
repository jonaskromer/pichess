package chess.model.board

import chess.model.piece.Color
import zio.test.*

object GameStateSpec extends ZIOSpecDefault:

  def spec = suite("GameState")(
    suite("initial")(
      test("set White as the active color") {
        assertTrue(GameState.initial.activeColor == Color.White)
      },
      test("use the standard initial board") {
        assertTrue(GameState.initial.board == Board.initial)
      },
      test("have no en passant target initially") {
        assertTrue(GameState.initial.enPassantTarget == None)
      }
    ),
    // ─── Game status ───────────────────────────────────────────────────────────
    suite("game status")(
      test("initial state has status Playing") {
        assertTrue(GameState.initial.status == GameStatus.Playing)
      },
      test("GameState preserves status through copy") {
        val s =
          GameState.initial.copy(status = GameStatus.Checkmate(Color.White))
        assertTrue(s.status == GameStatus.Checkmate(Color.White))
      }
    ),
    // ─── Castling rights ──────────────────────────────────────────────────────
    suite("castling rights")(
      test("initial state has all four castling rights") {
        val cr = GameState.initial.castlingRights
        assertTrue(
          cr.whiteKingSide,
          cr.whiteQueenSide,
          cr.blackKingSide,
          cr.blackQueenSide
        )
      },
      test("castling rights can be constructed with specific flags") {
        val cr = CastlingRights(
          whiteKingSide = false,
          whiteQueenSide = true,
          blackKingSide = true,
          blackQueenSide = false
        )
        assertTrue(
          !cr.whiteKingSide,
          cr.whiteQueenSide,
          cr.blackKingSide,
          !cr.blackQueenSide
        )
      },
      test("GameState preserves castling rights through copy") {
        val cr = CastlingRights(
          whiteKingSide = false,
          whiteQueenSide = false,
          blackKingSide = true,
          blackQueenSide = true
        )
        val s = GameState.initial.copy(castlingRights = cr)
        assertTrue(
          !s.castlingRights.whiteKingSide,
          !s.castlingRights.whiteQueenSide,
          s.castlingRights.blackKingSide,
          s.castlingRights.blackQueenSide
        )
      }
    )
  )
