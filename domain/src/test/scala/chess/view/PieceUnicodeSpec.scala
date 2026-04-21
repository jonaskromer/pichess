package chess.view

import chess.model.piece.{Color, Piece, PieceType}
import zio.test.*

object PieceUnicodeSpec extends ZIOSpecDefault:

  def spec = suite("PieceUnicode")(
    test("return correct white piece symbols") {
      assertTrue(
        PieceUnicode(Piece(Color.White, PieceType.King)) == '\u2654',
        PieceUnicode(Piece(Color.White, PieceType.Queen)) == '\u2655',
        PieceUnicode(Piece(Color.White, PieceType.Rook)) == '\u2656',
        PieceUnicode(Piece(Color.White, PieceType.Bishop)) == '\u2657',
        PieceUnicode(Piece(Color.White, PieceType.Knight)) == '\u2658',
        PieceUnicode(Piece(Color.White, PieceType.Pawn)) == '\u2659'
      )
    },
    test("return correct black piece symbols") {
      assertTrue(
        PieceUnicode(Piece(Color.Black, PieceType.King)) == '\u265a',
        PieceUnicode(Piece(Color.Black, PieceType.Queen)) == '\u265b',
        PieceUnicode(Piece(Color.Black, PieceType.Rook)) == '\u265c',
        PieceUnicode(Piece(Color.Black, PieceType.Bishop)) == '\u265d',
        PieceUnicode(Piece(Color.Black, PieceType.Knight)) == '\u265e',
        PieceUnicode(Piece(Color.Black, PieceType.Pawn)) == '\u265f'
      )
    }
  )
