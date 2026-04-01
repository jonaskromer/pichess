package chess.model.rules

import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{Board, GameState, Move, Position}
import chess.model.GameError
import zio.*

object Game:
  private val promotionPieces: Set[PieceType] =
    Set(PieceType.Queen, PieceType.Rook, PieceType.Bishop, PieceType.Knight)

  def applyMove(
      state: GameState,
      move: Move
  ): IO[GameError, GameState] =
    for
      _ <- MoveValidator.validate(state, move)
      piece = state.board(move.from)
      _ <- validatePromotion(piece, move)
      movedPiece = promotedPiece(piece, move)
    yield GameState(
      board = updatedBoard(state, move, movedPiece),
      activeColor = state.activeColor.opposite,
      enPassantTarget = nextEnPassantTarget(move, piece)
    )

  private def isPromotionRank(piece: Piece, row: Int): Boolean =
    piece.pieceType == PieceType.Pawn &&
      ((piece.color == Color.White && row == 8) ||
        (piece.color == Color.Black && row == 1))

  private def validatePromotion(
      piece: Piece,
      move: Move
  ): IO[GameError, Unit] =
    val reachesBackRank = isPromotionRank(piece, move.to.row)
    move.promotion match
      case Some(pt) if !reachesBackRank =>
        ZIO.fail(
          GameError.InvalidMove(
            "Promotion is only allowed when a pawn reaches the back rank"
          )
        )
      case Some(pt) if !promotionPieces.contains(pt) =>
        ZIO.fail(
          GameError.InvalidMove(
            "Pawn must promote to Queen, Rook, Bishop, or Knight"
          )
        )
      case None if reachesBackRank =>
        ZIO.fail(
          GameError.InvalidMove(
            "Pawn reaching the back rank must promote (e.g. e8=Q)"
          )
        )
      case _ => ZIO.unit

  private def promotedPiece(piece: Piece, move: Move): Piece =
    move.promotion match
      case Some(pt) => piece.copy(pieceType = pt)
      case None     => piece

  private def updatedBoard(state: GameState, move: Move, piece: Piece) =
    val base = state.board - move.from + (move.to -> piece)
    if isEnPassantCapture(state, move, piece) then
      base - Position(move.to.col, move.from.row)
    else base

  private def isEnPassantCapture(
      state: GameState,
      move: Move,
      piece: Piece
  ): Boolean =
    piece.pieceType == PieceType.Pawn &&
      move.to.col != move.from.col &&
      state.enPassantTarget.contains(move.to)

  private def nextEnPassantTarget(move: Move, piece: Piece): Option[Position] =
    if piece.pieceType == PieceType.Pawn && Math.abs(
        move.to.row - move.from.row
      ) == 2
    then Some(Position(move.from.col, (move.from.row + move.to.row) / 2))
    else None
