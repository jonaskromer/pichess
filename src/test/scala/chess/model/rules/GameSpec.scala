package chess.model.rules

import chess.model.GameError
import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{GameState, Move, Position}
import zio.*
import zio.test.*

object GameSpec extends ZIOSpecDefault:

  private val initial = GameState.initial

  private lazy val enPassantState = GameState(
    Map(
      Position('e', 5) -> Piece(Color.White, PieceType.Pawn),
      Position('d', 5) -> Piece(Color.Black, PieceType.Pawn)
    ),
    Color.White,
    enPassantTarget = Some(Position('d', 6))
  )

  def spec = suite("Game.applyMove")(
    test("move a piece from source to destination") {
      for result <- Game.applyMove(initial, Move(Position('e', 2), Position('e', 4)))
      yield assertTrue(result.board(Position('e', 4)) == Piece(Color.White, PieceType.Pawn))
    },
    test("empty the source square after a move") {
      for result <- Game.applyMove(initial, Move(Position('e', 2), Position('e', 4)))
      yield assertTrue(result.board.get(Position('e', 2)).isEmpty)
    },
    test("switch active color from White to Black") {
      for result <- Game.applyMove(initial, Move(Position('e', 2), Position('e', 4)))
      yield assertTrue(result.activeColor == Color.Black)
    },
    test("switch active color from Black to White") {
      for
        afterWhite <- Game.applyMove(initial, Move(Position('e', 2), Position('e', 4)))
        result <- Game.applyMove(afterWhite, Move(Position('e', 7), Position('e', 5)))
      yield assertTrue(result.activeColor == Color.White)
    },
    test("capture an opponent piece by overwriting the destination") {
      val s = GameState(
        Map(
          Position('d', 1) -> Piece(Color.White, PieceType.Queen),
          Position('g', 4) -> Piece(Color.Black, PieceType.Pawn)
        ),
        Color.White
      )
      for result <- Game.applyMove(s, Move(Position('d', 1), Position('g', 4)))
      yield assertTrue(result.board(Position('g', 4)) == Piece(Color.White, PieceType.Queen))
    },
    test("fail when the source square is empty") {
      for err <- Game.applyMove(initial, Move(Position('e', 4), Position('e', 5))).flip
      yield assertTrue(err.message.contains("e4"))
    },
    test("fail when moving an opponent's piece") {
      for err <- Game.applyMove(initial, Move(Position('e', 7), Position('e', 5))).flip
      yield assertTrue(err.message.contains("Black"))
    },
    // ─── En passant target tracking ─────────────────────────────────────────────
    suite("en passant target")(
      test("set after white pawn double advance") {
        for result <- Game.applyMove(initial, Move(Position('e', 2), Position('e', 4)))
        yield assertTrue(result.enPassantTarget == Some(Position('e', 3)))
      },
      test("set after black pawn double advance") {
        for
          afterWhite <- Game.applyMove(initial, Move(Position('e', 2), Position('e', 4)))
          result <- Game.applyMove(afterWhite, Move(Position('d', 7), Position('d', 5)))
        yield assertTrue(result.enPassantTarget == Some(Position('d', 6)))
      },
      test("clear after a non-double-advance move") {
        for
          withTarget <- Game.applyMove(initial, Move(Position('e', 2), Position('e', 4)))
          result <- Game.applyMove(withTarget, Move(Position('d', 7), Position('d', 6)))
        yield assertTrue(result.enPassantTarget.isEmpty)
      },
      test("overwrite when a second double advance follows") {
        for
          afterWhite <- Game.applyMove(initial, Move(Position('e', 2), Position('e', 4)))
          afterBlack <- Game.applyMove(afterWhite, Move(Position('d', 7), Position('d', 5)))
        yield assertTrue(
          afterWhite.enPassantTarget == Some(Position('e', 3)),
          afterBlack.enPassantTarget == Some(Position('d', 6))
        )
      },
      test("clear after a single pawn advance") {
        for
          withTarget <- Game.applyMove(initial, Move(Position('e', 2), Position('e', 4)))
          result <- Game.applyMove(withTarget, Move(Position('a', 7), Position('a', 6)))
        yield assertTrue(result.enPassantTarget.isEmpty)
      }
    ),
    // ─── En passant capture mechanics ───────────────────────────────────────────
    suite("en passant capture")(
      test("place pawn on target square") {
        for result <- Game.applyMove(enPassantState, Move(Position('e', 5), Position('d', 6)))
        yield assertTrue(
          result.board.get(Position('d', 6)) == Some(Piece(Color.White, PieceType.Pawn))
        )
      },
      test("remove captured pawn") {
        for result <- Game.applyMove(enPassantState, Move(Position('e', 5), Position('d', 6)))
        yield assertTrue(result.board.get(Position('d', 5)).isEmpty)
      },
      test("clear en passant target") {
        for result <- Game.applyMove(enPassantState, Move(Position('e', 5), Position('d', 6)))
        yield assertTrue(result.enPassantTarget.isEmpty)
      }
    ),
    // ─── Pawn promotion ───────────────────────────────────────────────────────
    suite("pawn promotion")(
      test("promote white pawn to queen on rank 8") {
        val s = GameState(Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)), Color.White)
        for result <- Game.applyMove(s, Move(Position('e', 7), Position('e', 8), Some(PieceType.Queen)))
        yield assertTrue(result.board(Position('e', 8)) == Piece(Color.White, PieceType.Queen))
      },
      test("promote white pawn to knight (underpromotion)") {
        val s = GameState(Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)), Color.White)
        for result <- Game.applyMove(s, Move(Position('e', 7), Position('e', 8), Some(PieceType.Knight)))
        yield assertTrue(result.board(Position('e', 8)) == Piece(Color.White, PieceType.Knight))
      },
      test("promote black pawn to queen on rank 1") {
        val s = GameState(Map(Position('d', 2) -> Piece(Color.Black, PieceType.Pawn)), Color.Black)
        for result <- Game.applyMove(s, Move(Position('d', 2), Position('d', 1), Some(PieceType.Queen)))
        yield assertTrue(result.board(Position('d', 1)) == Piece(Color.Black, PieceType.Queen))
      },
      test("reject pawn reaching back rank without promotion") {
        val s = GameState(Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)), Color.White)
        for err <- Game.applyMove(s, Move(Position('e', 7), Position('e', 8))).flip
        yield assertTrue(err.message.contains("must promote"))
      },
      test("reject promotion on a non-back-rank move") {
        val s = GameState(Map(Position('e', 2) -> Piece(Color.White, PieceType.Pawn)), Color.White)
        for err <- Game.applyMove(s, Move(Position('e', 2), Position('e', 3), Some(PieceType.Queen))).flip
        yield assertTrue(err.message.contains("back rank"))
      },
      test("reject promotion to King") {
        val s = GameState(Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)), Color.White)
        for err <- Game.applyMove(s, Move(Position('e', 7), Position('e', 8), Some(PieceType.King))).flip
        yield assertTrue(err.message.contains("Queen, Rook, Bishop, or Knight"))
      },
      test("remove pawn from source after promotion") {
        val s = GameState(Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)), Color.White)
        for result <- Game.applyMove(s, Move(Position('e', 7), Position('e', 8), Some(PieceType.Queen)))
        yield assertTrue(result.board.get(Position('e', 7)).isEmpty)
      },
      test("promote via capture") {
        val s = GameState(
          Map(
            Position('e', 7) -> Piece(Color.White, PieceType.Pawn),
            Position('d', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.White
        )
        for result <- Game.applyMove(s, Move(Position('e', 7), Position('d', 8), Some(PieceType.Queen)))
        yield assertTrue(result.board(Position('d', 8)) == Piece(Color.White, PieceType.Queen))
      }
    )
  )
