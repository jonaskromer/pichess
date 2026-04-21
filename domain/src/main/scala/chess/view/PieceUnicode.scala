package chess.view

import chess.model.piece.{Color, Piece, PieceType}
import Color.*
import PieceType.*

object PieceUnicode:

  private val table: Map[(Color, PieceType), Char] = Map(
    (White, King) -> '\u2654',
    (White, Queen) -> '\u2655',
    (White, Rook) -> '\u2656',
    (White, Bishop) -> '\u2657',
    (White, Knight) -> '\u2658',
    (White, Pawn) -> '\u2659',
    (Black, King) -> '\u265a',
    (Black, Queen) -> '\u265b',
    (Black, Rook) -> '\u265c',
    (Black, Bishop) -> '\u265d',
    (Black, Knight) -> '\u265e',
    (Black, Pawn) -> '\u265f'
  )

  def apply(piece: Piece): Char = table((piece.color, piece.pieceType))
