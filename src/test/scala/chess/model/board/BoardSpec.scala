package chess.model.board

import chess.model.piece.{Color, Piece, PieceType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BoardSpec extends AnyFlatSpec with Matchers:

  private val board = Board.initial

  "Board.initial" should "contain exactly 32 pieces" in:
    board.size shouldBe 32

  it should "have the White King at e1" in:
    board(Position('e', 1)) shouldBe Piece(Color.White, PieceType.King)

  it should "have the Black King at e8" in:
    board(Position('e', 8)) shouldBe Piece(Color.Black, PieceType.King)

  it should "have the White Queen at d1" in:
    board(Position('d', 1)) shouldBe Piece(Color.White, PieceType.Queen)

  it should "have the Black Queen at d8" in:
    board(Position('d', 8)) shouldBe Piece(Color.Black, PieceType.Queen)

  it should "have White Rooks at a1 and h1" in:
    board(Position('a', 1)) shouldBe Piece(Color.White, PieceType.Rook)
    board(Position('h', 1)) shouldBe Piece(Color.White, PieceType.Rook)

  it should "have Black Rooks at a8 and h8" in:
    board(Position('a', 8)) shouldBe Piece(Color.Black, PieceType.Rook)
    board(Position('h', 8)) shouldBe Piece(Color.Black, PieceType.Rook)

  it should "have White Knights at b1 and g1" in:
    board(Position('b', 1)) shouldBe Piece(Color.White, PieceType.Knight)
    board(Position('g', 1)) shouldBe Piece(Color.White, PieceType.Knight)

  it should "have Black Knights at b8 and g8" in:
    board(Position('b', 8)) shouldBe Piece(Color.Black, PieceType.Knight)
    board(Position('g', 8)) shouldBe Piece(Color.Black, PieceType.Knight)

  it should "have White Bishops at c1 and f1" in:
    board(Position('c', 1)) shouldBe Piece(Color.White, PieceType.Bishop)
    board(Position('f', 1)) shouldBe Piece(Color.White, PieceType.Bishop)

  it should "have Black Bishops at c8 and f8" in:
    board(Position('c', 8)) shouldBe Piece(Color.Black, PieceType.Bishop)
    board(Position('f', 8)) shouldBe Piece(Color.Black, PieceType.Bishop)

  it should "have White Pawns on all of row 2" in:
    ('a' to 'h').foreach: col =>
      board(Position(col, 2)) shouldBe Piece(Color.White, PieceType.Pawn)

  it should "have Black Pawns on all of row 7" in:
    ('a' to 'h').foreach: col =>
      board(Position(col, 7)) shouldBe Piece(Color.Black, PieceType.Pawn)

  it should "have no pieces on rows 3 through 6" in:
    for
      col <- 'a' to 'h'
      row <- 3 to 6
    do board.get(Position(col, row)) shouldBe None
