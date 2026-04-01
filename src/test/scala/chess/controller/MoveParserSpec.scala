package chess.controller

import chess.model.board.{GameState, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.*
import zio.test.*

object MoveParserSpec extends ZIOSpecDefault:

  private val initial = GameState.initial

  def spec = suite("MoveParser.parse")(
    suite("coordinate notation")(
      test("parse space-separated squares") {
        for move <- MoveParser.parse("e2 e4", initial)
        yield assertTrue(move == Move(Position('e', 2), Position('e', 4)))
      },
      test("parse corner squares") {
        for move <- MoveParser.parse("a1 h8", initial)
        yield assertTrue(move == Move(Position('a', 1), Position('h', 8)))
      },
      test("parse squares with multiple spaces") {
        for move <- MoveParser.parse("e2  e4", initial)
        yield assertTrue(move == Move(Position('e', 2), Position('e', 4)))
      },
      test("parse squares with no separator") {
        for move <- MoveParser.parse("e2e4", initial)
        yield assertTrue(move == Move(Position('e', 2), Position('e', 4)))
      },
      test("parse squares separated by a dash") {
        for move <- MoveParser.parse("e2-e4", initial)
        yield assertTrue(move == Move(Position('e', 2), Position('e', 4)))
      }
    ),
    suite("coordinate promotion")(
      test("parse with no separator") {
        for move <- MoveParser.parse("e7e8=Q", initial)
        yield assertTrue(
          move == Move(
            Position('e', 7),
            Position('e', 8),
            Some(PieceType.Queen)
          )
        )
      },
      test("parse with space separator") {
        for move <- MoveParser.parse("e7 e8=Q", initial)
        yield assertTrue(
          move == Move(
            Position('e', 7),
            Position('e', 8),
            Some(PieceType.Queen)
          )
        )
      },
      test("parse with dash separator") {
        for move <- MoveParser.parse("e7-e8=Q", initial)
        yield assertTrue(
          move == Move(
            Position('e', 7),
            Position('e', 8),
            Some(PieceType.Queen)
          )
        )
      }
    ),
    suite("pawn push (SAN)")(
      test("resolve from the initial position") {
        for move <- MoveParser.parse("e4", initial)
        yield assertTrue(move == Move(Position('e', 2), Position('e', 4)))
      },
      test("accept a check suffix") {
        val state = GameState(
          Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)),
          Color.White
        )
        for move <- MoveParser.parse("e8=Q+", state)
        yield assertTrue(move.to == Position('e', 8))
      },
      test("accept a checkmate suffix") {
        val state = GameState(
          Map(Position('d', 2) -> Piece(Color.Black, PieceType.Pawn)),
          Color.Black
        )
        for move <- MoveParser.parse("d1=Q#", state)
        yield assertTrue(move.to == Position('d', 1))
      }
    ),
    suite("pawn capture (SAN)")(
      test("resolve with file hint") {
        val state = GameState(
          Map(
            Position('e', 4) -> Piece(Color.White, PieceType.Pawn),
            Position('d', 5) -> Piece(Color.Black, PieceType.Pawn)
          ),
          Color.White
        )
        for move <- MoveParser.parse("exd5", state)
        yield assertTrue(move == Move(Position('e', 4), Position('d', 5)))
      },
      test("resolve a pawn capture with promotion") {
        val state = GameState(
          Map(
            Position('e', 7) -> Piece(Color.White, PieceType.Pawn),
            Position('d', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.White
        )
        for move <- MoveParser.parse("exd8=Q", state)
        yield assertTrue(
          move == Move(
            Position('e', 7),
            Position('d', 8),
            Some(PieceType.Queen)
          )
        )
      }
    ),
    suite("pawn promotion (SAN)")(
      test("resolve a queen promotion") {
        val state = GameState(
          Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)),
          Color.White
        )
        for move <- MoveParser.parse("e8=Q", state)
        yield assertTrue(
          move == Move(
            Position('e', 7),
            Position('e', 8),
            Some(PieceType.Queen)
          )
        )
      },
      test("resolve a knight underpromotion") {
        val state = GameState(
          Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)),
          Color.White
        )
        for move <- MoveParser.parse("e8=N", state)
        yield assertTrue(
          move == Move(
            Position('e', 7),
            Position('e', 8),
            Some(PieceType.Knight)
          )
        )
      },
      test("resolve a rook underpromotion") {
        val state = GameState(
          Map(Position('a', 2) -> Piece(Color.Black, PieceType.Pawn)),
          Color.Black
        )
        for move <- MoveParser.parse("a1=R", state)
        yield assertTrue(
          move == Move(Position('a', 2), Position('a', 1), Some(PieceType.Rook))
        )
      },
      test("resolve a bishop underpromotion") {
        val state = GameState(
          Map(Position('d', 7) -> Piece(Color.White, PieceType.Pawn)),
          Color.White
        )
        for move <- MoveParser.parse("d8=B", state)
        yield assertTrue(
          move == Move(
            Position('d', 7),
            Position('d', 8),
            Some(PieceType.Bishop)
          )
        )
      },
      test("resolve a capture with knight underpromotion") {
        val state = GameState(
          Map(
            Position('e', 7) -> Piece(Color.White, PieceType.Pawn),
            Position('d', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.White
        )
        for move <- MoveParser.parse("exd8=N", state)
        yield assertTrue(
          move == Move(
            Position('e', 7),
            Position('d', 8),
            Some(PieceType.Knight)
          )
        )
      }
    ),
    suite("piece moves (SAN)")(
      test("resolve a knight move") {
        for move <- MoveParser.parse("Nf3", initial)
        yield assertTrue(move == Move(Position('g', 1), Position('f', 3)))
      },
      test("resolve a piece capture") {
        val state = GameState(
          Map(
            Position('g', 1) -> Piece(Color.White, PieceType.Knight),
            Position('f', 3) -> Piece(Color.Black, PieceType.Pawn)
          ),
          Color.White
        )
        for move <- MoveParser.parse("Nxf3", state)
        yield assertTrue(move == Move(Position('g', 1), Position('f', 3)))
      },
      test("accept a check suffix") {
        for move <- MoveParser.parse("Nf3+", initial)
        yield assertTrue(move.to == Position('f', 3))
      },
      test("accept a checkmate suffix") {
        val state = GameState(
          Map(
            Position('h', 5) -> Piece(Color.White, PieceType.Queen),
            Position('f', 7) -> Piece(Color.Black, PieceType.Pawn)
          ),
          Color.White
        )
        for move <- MoveParser.parse("Qxf7#", state)
        yield assertTrue(move.to == Position('f', 7))
      }
    ),
    suite("disambiguation (SAN)")(
      test("resolve with file disambiguation") {
        val state = GameState(
          Map(
            Position('a', 1) -> Piece(Color.White, PieceType.Rook),
            Position('f', 1) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for move <- MoveParser.parse("Rae1", state)
        yield assertTrue(move == Move(Position('a', 1), Position('e', 1)))
      },
      test("resolve with rank disambiguation") {
        val state = GameState(
          Map(
            Position('a', 1) -> Piece(Color.White, PieceType.Rook),
            Position('a', 5) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for move <- MoveParser.parse("R1a3", state)
        yield assertTrue(move == Move(Position('a', 1), Position('a', 3)))
      }
    ),
    suite("castling")(
      test("parse O-O as king-side castling") {
        val state = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('h', 1) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for move <- MoveParser.parse("O-O", state)
        yield assertTrue(move == Move(Position('e', 1), Position('g', 1)))
      },
      test("parse O-O-O as queen-side castling") {
        val state = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('a', 1) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for move <- MoveParser.parse("O-O-O", state)
        yield assertTrue(move == Move(Position('e', 1), Position('c', 1)))
      },
      test("accept a check suffix on castling") {
        val state = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('h', 1) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for move <- MoveParser.parse("O-O+", state)
        yield assertTrue(move == Move(Position('e', 1), Position('g', 1)))
      }
    ),
    suite("errors")(
      test("reject three or more tokens") {
        for exit <- MoveParser.parse("e2 e4 d5", initial).exit
        yield assertTrue(exit.isFailure)
      },
      test("reject empty input") {
        for exit <- MoveParser.parse("", initial).exit
        yield assertTrue(exit.isFailure)
      },
      test("reject an invalid column") {
        for exit <- MoveParser.parse("i2 e4", initial).exit
        yield assertTrue(exit.isFailure)
      },
      test("reject a row above 8") {
        for exit <- MoveParser.parse("e9 e4", initial).exit
        yield assertTrue(exit.isFailure)
      },
      test("reject a row of 0") {
        for exit <- MoveParser.parse("e0 e4", initial).exit
        yield assertTrue(exit.isFailure)
      },
      test("reject an invalid destination column") {
        for exit <- MoveParser.parse("e2 z4", initial).exit
        yield assertTrue(exit.isFailure)
      },
      test("reject an invalid destination row") {
        for exit <- MoveParser.parse("e2 e9", initial).exit
        yield assertTrue(exit.isFailure)
      },
      test("reject a token that is too long") {
        for exit <- MoveParser.parse("e22 e4", initial).exit
        yield assertTrue(exit.isFailure)
      },
      test("reject a token that is too short") {
        for exit <- MoveParser.parse("e e4", initial).exit
        yield assertTrue(exit.isFailure)
      },
      test("include a hint pointing to help") {
        for err <- MoveParser.parse("nonsense", initial).flip
        yield assertTrue(err.message.contains("help"))
      }
    )
  )
