package chess.controller

import chess.model.board.Position
import chess.model.piece.PieceType

enum ParsedMove:
  case Coordinate(from: Position, to: Position)
  case San(
      piece: PieceType,
      dest: Position,
      disambigFile: Option[Char],
      disambigRank: Option[Int]
  )
  case Castling(kingside: Boolean)
