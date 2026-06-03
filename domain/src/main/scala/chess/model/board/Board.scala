package chess.model.board

import chess.model.piece.Color.*
import chess.model.piece.Piece
import chess.model.piece.PieceType.*

/** The chess board representation. Phase 1 of the bitboard migration —
  * `Board` is now a bitboard-backed [[BoardState]], but the existing
  * `Map[Position, Piece]`-style call surface (get/contains/exists/
  * collectFirst/+/-/iterator/foldLeft/size) is preserved on the new
  * type so consumers don't need to be rewritten. The performance win
  * lands in Phase 2 when MoveValidator's hot paths switch to bitboard
  * intrinsics directly.
  */
type Board = BoardState

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

    BoardState.from(whitePieces ++ whitePawns ++ blackPieces ++ blackPawns)
