package chess.model.rules

import chess.model.piece.{Piece, PieceType}
import chess.model.board.{Board, GameState, Move, Position}

object Game:
  def applyMove(state: GameState, move: Move): Either[String, GameState] =
    MoveValidator
      .validate(state, move)
      .map: _ =>
        val piece = state.board(move.from)
        GameState(
          board = updatedBoard(state, move, piece),
          activeColor = state.activeColor.opposite,
          enPassantTarget = nextEnPassantTarget(move, piece)
        )

  private def updatedBoard(state: GameState, move: Move, piece: Piece) =
    val base = state.board - move.from + (move.to -> piece)
    if isEnPassantCapture(state, move, piece) then base - Position(move.to.col, move.from.row)
    else base

  private def isEnPassantCapture(state: GameState, move: Move, piece: Piece): Boolean =
    piece.pieceType == PieceType.Pawn &&
      move.to.col != move.from.col &&
      state.enPassantTarget.contains(move.to)

  private def nextEnPassantTarget(move: Move, piece: Piece): Option[Position] =
    if piece.pieceType == PieceType.Pawn && Math.abs(move.to.row - move.from.row) == 2 then
      Some(Position(move.from.col, (move.from.row + move.to.row) / 2))
    else None
