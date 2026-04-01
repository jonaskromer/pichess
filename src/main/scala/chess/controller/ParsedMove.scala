package chess.controller

import chess.model.board.Position
import chess.model.piece.PieceType

enum ParsedMove:
  case Coordinate(from: Position, to: Position, promotion: Option[PieceType] = None)
  case San(
      piece: PieceType,
      dest: Position,
      disambigFile: Option[Char],
      disambigRank: Option[Int],
      promotion: Option[PieceType] = None
  )
  case Castling(kingside: Boolean)
