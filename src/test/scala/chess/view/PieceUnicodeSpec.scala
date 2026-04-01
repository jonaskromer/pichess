package chess.view

import chess.model.piece.{Color, Piece, PieceType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PieceUnicodeSpec extends AnyFlatSpec with Matchers:

  "PieceUnicode" should "return correct white piece symbols" in:
    PieceUnicode(Piece(Color.White, PieceType.King)) shouldBe '\u2654'
    PieceUnicode(Piece(Color.White, PieceType.Queen)) shouldBe '\u2655'
    PieceUnicode(Piece(Color.White, PieceType.Rook)) shouldBe '\u2656'
    PieceUnicode(Piece(Color.White, PieceType.Bishop)) shouldBe '\u2657'
    PieceUnicode(Piece(Color.White, PieceType.Knight)) shouldBe '\u2658'
    PieceUnicode(Piece(Color.White, PieceType.Pawn)) shouldBe '\u2659'

  it should "return correct black piece symbols" in:
    PieceUnicode(Piece(Color.Black, PieceType.King)) shouldBe '\u265a'
    PieceUnicode(Piece(Color.Black, PieceType.Queen)) shouldBe '\u265b'
    PieceUnicode(Piece(Color.Black, PieceType.Rook)) shouldBe '\u265c'
    PieceUnicode(Piece(Color.Black, PieceType.Bishop)) shouldBe '\u265d'
    PieceUnicode(Piece(Color.Black, PieceType.Knight)) shouldBe '\u265e'
    PieceUnicode(Piece(Color.Black, PieceType.Pawn)) shouldBe '\u265f'
