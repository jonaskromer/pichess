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
      },
      test("isPlaying / isOver reflect the current status") {
        assertTrue(
          GameStatus.Playing.isPlaying,
          !GameStatus.Playing.isOver,
          GameStatus.Checkmate(Color.White).isOver,
          !GameStatus.Checkmate(Color.White).isPlaying,
          GameStatus.Draw(DrawReason.Stalemate).isOver,
          !GameStatus.Draw(DrawReason.Stalemate).isPlaying
        )
      }
    ),
    // ─── Terminal transition: endWith ──────────────────────────────────────────
    suite("endWith")(
      test("transitions from Playing to the supplied terminal status") {
        val s = GameState.initial.endWith(GameStatus.Checkmate(Color.White))
        assertTrue(s.status == GameStatus.Checkmate(Color.White))
      },
      test("transitions from Playing to a Draw status") {
        val s = GameState.initial.endWith(
          GameStatus.Draw(DrawReason.ThreefoldRepetition)
        )
        assertTrue(s.status == GameStatus.Draw(DrawReason.ThreefoldRepetition))
      },
      test(
        "is a no-op when the game is already over (Checkmate → Draw is rejected)"
      ) {
        val finished =
          GameState.initial.copy(status = GameStatus.Checkmate(Color.Black))
        val attempted =
          finished.endWith(GameStatus.Draw(DrawReason.Stalemate))
        // Terminal states are sticky — Checkmate cannot be rewritten to Draw
        assertTrue(
          attempted.status == GameStatus.Checkmate(Color.Black),
          attempted eq finished
        )
      },
      test(
        "is a no-op when the game is already Drawn (Draw → Checkmate is rejected)"
      ) {
        val finished = GameState.initial.copy(status =
          GameStatus.Draw(DrawReason.InsufficientMaterial)
        )
        val attempted =
          finished.endWith(GameStatus.Checkmate(Color.White))
        assertTrue(
          attempted.status == GameStatus.Draw(DrawReason.InsufficientMaterial),
          attempted eq finished
        )
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
