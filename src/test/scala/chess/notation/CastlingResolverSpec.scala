package chess.notation

import chess.model.board.{GameState, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.*
import zio.test.*

object CastlingResolverSpec extends ZIOSpecDefault:

  private val WK = Piece(Color.White, PieceType.King)
  private val WR = Piece(Color.White, PieceType.Rook)
  private val BK = Piece(Color.Black, PieceType.King)
  private val BR = Piece(Color.Black, PieceType.Rook)

  private val castlingReady = GameState(
    Map(
      Position('e', 1) -> WK,
      Position('a', 1) -> WR,
      Position('h', 1) -> WR,
      Position('e', 8) -> BK,
      Position('a', 8) -> BR,
      Position('h', 8) -> BR
    ),
    Color.White
  )

  private val blackToMove = castlingReady.copy(activeColor = Color.Black)

  def spec = suite("CastlingResolver")(
    suite("white")(
      test("parse O-O as king-side castling move e1 to g1") {
        for result <- CastlingResolver.parse("O-O", castlingReady)
        yield assertTrue(
          result == Some(Move(Position('e', 1), Position('g', 1)))
        )
      },
      test("parse O-O-O as queen-side castling move e1 to c1") {
        for result <- CastlingResolver.parse("O-O-O", castlingReady)
        yield assertTrue(
          result == Some(Move(Position('e', 1), Position('c', 1)))
        )
      }
    ),
    suite("black")(
      test("parse O-O as king-side castling move e8 to g8") {
        for result <- CastlingResolver.parse("O-O", blackToMove)
        yield assertTrue(
          result == Some(Move(Position('e', 8), Position('g', 8)))
        )
      },
      test("parse O-O-O as queen-side castling move e8 to c8") {
        for result <- CastlingResolver.parse("O-O-O", blackToMove)
        yield assertTrue(
          result == Some(Move(Position('e', 8), Position('c', 8)))
        )
      }
    ),
    suite("non-castling input")(
      test("return None for regular move notation") {
        for result <- CastlingResolver.parse("e4", castlingReady)
        yield assertTrue(result.isEmpty)
      },
      test("return None for coordinate notation") {
        for result <- CastlingResolver.parse("e2 e4", castlingReady)
        yield assertTrue(result.isEmpty)
      }
    ),
    suite("with check/mate suffix")(
      test("parse O-O+ as king-side castling") {
        for result <- CastlingResolver.parse("O-O+", castlingReady)
        yield assertTrue(
          result == Some(Move(Position('e', 1), Position('g', 1)))
        )
      },
      test("parse O-O-O# as queen-side castling") {
        for result <- CastlingResolver.parse("O-O-O#", castlingReady)
        yield assertTrue(
          result == Some(Move(Position('e', 1), Position('c', 1)))
        )
      }
    )
  )
