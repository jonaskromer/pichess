package chess.notation

import chess.model.board.{GameState, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.*
import zio.test.*

object SanSerializerSpec extends ZIOSpecDefault:

  private val initial = GameState.initial

  def spec = suite("SanSerializer.toSan")(
    suite("pawn")(
      test("render a pawn push") {
        for san <- SanSerializer.toSan(Move(Position('e', 2), Position('e', 4)), initial)
        yield assertTrue(san == "e4")
      },
      test("render a single-square pawn push") {
        for san <- SanSerializer.toSan(Move(Position('e', 2), Position('e', 3)), initial)
        yield assertTrue(san == "e3")
      },
      test("render a pawn capture with file prefix") {
        val state = GameState(
          Map(
            Position('e', 4) -> Piece(Color.White, PieceType.Pawn),
            Position('d', 5) -> Piece(Color.Black, PieceType.Pawn)
          ),
          Color.White
        )
        for san <- SanSerializer.toSan(Move(Position('e', 4), Position('d', 5)), state)
        yield assertTrue(san == "exd5")
      },
      test("render an en passant capture") {
        val state = GameState(
          Map(
            Position('e', 5) -> Piece(Color.White, PieceType.Pawn),
            Position('d', 5) -> Piece(Color.Black, PieceType.Pawn)
          ),
          Color.White,
          enPassantTarget = Some(Position('d', 6))
        )
        for san <- SanSerializer.toSan(Move(Position('e', 5), Position('d', 6)), state)
        yield assertTrue(san == "exd6")
      },
      test("render a promotion") {
        val state = GameState(
          Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)),
          Color.White
        )
        for san <- SanSerializer.toSan(Move(Position('e', 7), Position('e', 8), Some(PieceType.Queen)), state)
        yield assertTrue(san == "e8=Q")
      },
      test("render a capture with promotion") {
        val state = GameState(
          Map(
            Position('e', 7) -> Piece(Color.White, PieceType.Pawn),
            Position('d', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.White
        )
        for san <- SanSerializer.toSan(Move(Position('e', 7), Position('d', 8), Some(PieceType.Knight)), state)
        yield assertTrue(san == "exd8=N")
      }
    ),
    suite("piece")(
      test("render a knight move") {
        for san <- SanSerializer.toSan(Move(Position('g', 1), Position('f', 3)), initial)
        yield assertTrue(san == "Nf3")
      },
      test("render a piece capture") {
        val state = GameState(
          Map(
            Position('d', 1) -> Piece(Color.White, PieceType.Queen),
            Position('g', 4) -> Piece(Color.Black, PieceType.Pawn)
          ),
          Color.White
        )
        for san <- SanSerializer.toSan(Move(Position('d', 1), Position('g', 4)), state)
        yield assertTrue(san == "Qxg4")
      },
      test("render a king move") {
        val state = GameState(
          Map(Position('e', 1) -> Piece(Color.White, PieceType.King)),
          Color.White
        )
        for san <- SanSerializer.toSan(Move(Position('e', 1), Position('f', 2)), state)
        yield assertTrue(san == "Kf2")
      }
    ),
    suite("disambiguation")(
      test("add file when two pieces share a rank") {
        val state = GameState(
          Map(
            Position('a', 1) -> Piece(Color.White, PieceType.Rook),
            Position('f', 1) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for san <- SanSerializer.toSan(Move(Position('a', 1), Position('e', 1)), state)
        yield assertTrue(san == "Rae1")
      },
      test("add rank when two pieces share a file") {
        val state = GameState(
          Map(
            Position('a', 1) -> Piece(Color.White, PieceType.Rook),
            Position('a', 5) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for san <- SanSerializer.toSan(Move(Position('a', 1), Position('a', 3)), state)
        yield assertTrue(san == "R1a3")
      },
      test("add file and rank when both are needed") {
        val state = GameState(
          Map(
            Position('d', 4) -> Piece(Color.White, PieceType.Queen),
            Position('d', 1) -> Piece(Color.White, PieceType.Queen),
            Position('a', 4) -> Piece(Color.White, PieceType.Queen)
          ),
          Color.White
        )
        for san <- SanSerializer.toSan(Move(Position('d', 4), Position('a', 1)), state)
        yield assertTrue(san == "Qd4a1")
      },
      test("not disambiguate when only one piece can reach the destination") {
        for san <- SanSerializer.toSan(Move(Position('g', 1), Position('f', 3)), initial)
        yield assertTrue(san == "Nf3")
      }
    )
  )
