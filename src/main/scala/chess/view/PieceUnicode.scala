package chess.view

import chess.model.piece.{Color, Piece, PieceType}

object PieceUnicode:
  def apply(piece: Piece): Char =
    import Color.*
    import PieceType.*
    (piece.color, piece.pieceType) match
      case (White, King)   => '\u2654'
      case (White, Queen)  => '\u2655'
      case (White, Rook)   => '\u2656'
      case (White, Bishop) => '\u2657'
      case (White, Knight) => '\u2658'
      case (White, Pawn)   => '\u2659'
      case (Black, King)   => '\u265a'
      case (Black, Queen)  => '\u265b'
      case (Black, Rook)   => '\u265c'
      case (Black, Bishop) => '\u265d'
      case (Black, Knight) => '\u265e'
      case (Black, Pawn)   => '\u265f'
