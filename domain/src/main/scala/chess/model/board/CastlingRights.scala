package chess.model.board

case class CastlingRights(
    whiteKingSide: Boolean = true,
    whiteQueenSide: Boolean = true,
    blackKingSide: Boolean = true,
    blackQueenSide: Boolean = true
)
