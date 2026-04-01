package chess.model.rules

import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{Board, CastlingRights, GameState, Move, Position}
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
      newBoard = updatedBoard(state, move, movedPiece)
      _ <- ZIO.when(MoveValidator.isInCheck(newBoard, state.activeColor))(
        ZIO.fail(GameError.InvalidMove("King cannot be left in check"))
      )
    yield GameState(
      board = newBoard,
      activeColor = state.activeColor.opposite,
      enPassantTarget = nextEnPassantTarget(move, piece),
      inCheck = MoveValidator.isInCheck(newBoard, state.activeColor.opposite),
      castlingRights = updatedCastlingRights(state, move)
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
            "Pawn cannot promote unless it reaches the back rank"
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
            "Pawn must promote when reaching the back rank (e.g. e8=Q)"
          )
        )
      case _ => ZIO.unit

  private def promotedPiece(piece: Piece, move: Move): Piece =
    move.promotion match
      case Some(pt) => piece.copy(pieceType = pt)
      case None     => piece

  /** Compute the board after applying a move (no validation). Used by
    * SanSerializer for check suffix.
    */
  def applyMoveToBoard(state: GameState, move: Move, piece: Piece): Board =
    updatedBoard(state, move, promotedPiece(piece, move))

  private def isCastling(move: Move, piece: Piece): Boolean =
    piece.pieceType == PieceType.King && Math.abs(
      move.to.col - move.from.col
    ) == 2

  private def updatedBoard(state: GameState, move: Move, piece: Piece) =
    val base = state.board - move.from + (move.to -> piece)
    if isCastling(move, piece) then
      val rank = move.from.row
      val kingSide = move.to.col > move.from.col
      val rookFrom = Position(if kingSide then 'h' else 'a', rank)
      val rookTo = Position(if kingSide then 'f' else 'd', rank)
      val rook = state.board(rookFrom)
      base - rookFrom + (rookTo -> rook)
    else if isEnPassantCapture(state, move, piece) then
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

  // ─── Castling rights tracking ─────────────────────────────────────────────

  private def updatedCastlingRights(
      state: GameState,
      move: Move
  ): CastlingRights =
    val piece = state.board(move.from)
    val cr = state.castlingRights

    // Revoke rights based on the piece that moved
    val afterMove = piece.pieceType match
      case PieceType.King if piece.color == Color.White =>
        cr.copy(whiteKingSide = false, whiteQueenSide = false)
      case PieceType.King if piece.color == Color.Black =>
        cr.copy(blackKingSide = false, blackQueenSide = false)
      case PieceType.Rook =>
        (move.from.col, move.from.row, piece.color) match
          case ('h', 1, Color.White) => cr.copy(whiteKingSide = false)
          case ('a', 1, Color.White) => cr.copy(whiteQueenSide = false)
          case ('h', 8, Color.Black) => cr.copy(blackKingSide = false)
          case ('a', 8, Color.Black) => cr.copy(blackQueenSide = false)
          case _                     => cr
      case _ => cr

    // Revoke rights when a rook is captured on its starting square
    (move.to.col, move.to.row) match
      case ('h', 1) => afterMove.copy(whiteKingSide = false)
      case ('a', 1) => afterMove.copy(whiteQueenSide = false)
      case ('h', 8) => afterMove.copy(blackKingSide = false)
      case ('a', 8) => afterMove.copy(blackQueenSide = false)
      case _        => afterMove
