package chess.model.board

import chess.model.piece.{Color, Piece, PieceType}
import Color.*
import PieceType.*

type Board = Map[Position, Piece]

object Board:
  val initial: Board =
    val cols = 'a' to 'h'
    val backRank = List(Rook, Knight, Bishop, Queen, King, Bishop, Knight, Rook)

    val whitePieces =
      cols.zip(backRank).map((col, pt) => Position(col, 1) -> Piece(White, pt))
    val whitePawns = cols.map(col => Position(col, 2) -> Piece(White, Pawn))
    val blackPieces =
      cols.zip(backRank).map((col, pt) => Position(col, 8) -> Piece(Black, pt))
    val blackPawns = cols.map(col => Position(col, 7) -> Piece(Black, Pawn))

    (whitePieces ++ whitePawns ++ blackPieces ++ blackPawns).toMap
