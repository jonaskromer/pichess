package chess.notation

import chess.model.board.{GameState, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.*
import zio.test.*

object SanResolverSpec extends ZIOSpecDefault:

  private val initial = GameState.initial

  def spec = suite("SanResolver")(
    suite("pawn push")(
      test("find the pawn that can reach the destination") {
        for result <- SanResolver.parse("e4", initial)
        yield assertTrue(
          result == Some(Move(Position('e', 2), Position('e', 4)))
        )
      },
      test("return None for non-SAN input") {
        for result <- SanResolver.parse("e2 e4", initial)
        yield assertTrue(result.isEmpty)
      }
    ),
    suite("pawn capture")(
      test("use the file hint to find the correct pawn") {
        val state = GameState(
          Map(
            Position('e', 4) -> Piece(Color.White, PieceType.Pawn),
            Position('d', 5) -> Piece(Color.Black, PieceType.Pawn)
          ),
          Color.White
        )
        for result <- SanResolver.parse("exd5", state)
        yield assertTrue(
          result == Some(Move(Position('e', 4), Position('d', 5)))
        )
      }
    ),
    suite("piece move")(
      test("find the knight that can reach the destination") {
        for result <- SanResolver.parse("Nf3", initial)
        yield assertTrue(
          result == Some(Move(Position('g', 1), Position('f', 3)))
        )
      },
      test("fail when no piece of that type can reach the destination") {
        for exit <- SanResolver.parse("Re4", initial).exit
        yield assertTrue(exit.isFailure)
      },
      test("fail when the move is ambiguous") {
        val state = GameState(
          Map(
            Position('a', 1) -> Piece(Color.White, PieceType.Rook),
            Position('f', 1) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for exit <- SanResolver.parse("Re1", state).exit
        yield assertTrue(exit.isFailure)
      }
    ),
    suite("disambiguation")(
      test("select the piece on the given file") {
        val state = GameState(
          Map(
            Position('a', 1) -> Piece(Color.White, PieceType.Rook),
            Position('f', 1) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for result <- SanResolver.parse("Rae1", state)
        yield assertTrue(
          result == Some(Move(Position('a', 1), Position('e', 1)))
        )
      },
      test("select the piece on the given rank") {
        val state = GameState(
          Map(
            Position('a', 1) -> Piece(Color.White, PieceType.Rook),
            Position('a', 5) -> Piece(Color.White, PieceType.Rook)
          ),
          Color.White
        )
        for result <- SanResolver.parse("R1a3", state)
        yield assertTrue(
          result == Some(Move(Position('a', 1), Position('a', 3)))
        )
      }
    )
  )
