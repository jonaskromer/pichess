package chess.model.board

import chess.model.piece.PieceType

case class Move(from: Position, to: Position, promotion: Option[PieceType] = None)
