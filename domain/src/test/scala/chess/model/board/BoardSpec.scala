package chess.model.board

import chess.model.piece.{Color, Piece, PieceType}
import zio.test.*

object BoardSpec extends ZIOSpecDefault:

  private val board = Board.initial

  def spec = suite("Board.initial")(
    test("contain exactly 32 pieces") {
      assertTrue(board.size == 32)
    },
    test("have the White King at e1") {
      assertTrue(board(Position('e', 1)) == Piece(Color.White, PieceType.King))
    },
    test("have the Black King at e8") {
      assertTrue(board(Position('e', 8)) == Piece(Color.Black, PieceType.King))
    },
    test("have the White Queen at d1") {
      assertTrue(board(Position('d', 1)) == Piece(Color.White, PieceType.Queen))
    },
    test("have the Black Queen at d8") {
      assertTrue(board(Position('d', 8)) == Piece(Color.Black, PieceType.Queen))
    },
    test("have White Rooks at a1 and h1") {
      assertTrue(
        board(Position('a', 1)) == Piece(Color.White, PieceType.Rook),
        board(Position('h', 1)) == Piece(Color.White, PieceType.Rook)
      )
    },
    test("have Black Rooks at a8 and h8") {
      assertTrue(
        board(Position('a', 8)) == Piece(Color.Black, PieceType.Rook),
        board(Position('h', 8)) == Piece(Color.Black, PieceType.Rook)
      )
    },
    test("have White Knights at b1 and g1") {
      assertTrue(
        board(Position('b', 1)) == Piece(Color.White, PieceType.Knight),
        board(Position('g', 1)) == Piece(Color.White, PieceType.Knight)
      )
    },
    test("have Black Knights at b8 and g8") {
      assertTrue(
        board(Position('b', 8)) == Piece(Color.Black, PieceType.Knight),
        board(Position('g', 8)) == Piece(Color.Black, PieceType.Knight)
      )
    },
    test("have White Bishops at c1 and f1") {
      assertTrue(
        board(Position('c', 1)) == Piece(Color.White, PieceType.Bishop),
        board(Position('f', 1)) == Piece(Color.White, PieceType.Bishop)
      )
    },
    test("have Black Bishops at c8 and f8") {
      assertTrue(
        board(Position('c', 8)) == Piece(Color.Black, PieceType.Bishop),
        board(Position('f', 8)) == Piece(Color.Black, PieceType.Bishop)
      )
    },
    test("have White Pawns on all of row 2") {
      assertTrue(
        ('a' to 'h').forall(col =>
          board(Position(col, 2)) == Piece(Color.White, PieceType.Pawn)
        )
      )
    },
    test("have Black Pawns on all of row 7") {
      assertTrue(
        ('a' to 'h').forall(col =>
          board(Position(col, 7)) == Piece(Color.Black, PieceType.Pawn)
        )
      )
    },
    test("have no pieces on rows 3 through 6") {
      assertTrue(
        (for
          col <- 'a' to 'h'
          row <- 3 to 6
        yield board.get(Position(col, row))).forall(_ == None)
      )
    }
  )
