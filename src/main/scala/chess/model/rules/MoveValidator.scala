package chess.model.rules

import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{Board, GameState, Move, Position}

object MoveValidator:

  // ─── Move geometry ──────────────────────────────────────────────────────────

  private enum Shape:
    case Horizontal, Vertical, Diagonal, KnightLeap, Irregular

  private def classifyShape(move: Move): Shape =
    val dc = Math.abs(move.to.col - move.from.col)
    val dr = Math.abs(move.to.row - move.from.row)
    if dr == 0 && dc > 0 then Shape.Horizontal
    else if dc == 0 && dr > 0 then Shape.Vertical
    else if dc == dr && dc > 0 then Shape.Diagonal
    else if (dc == 2 && dr == 1) || (dc == 1 && dr == 2) then Shape.KnightLeap
    else Shape.Irregular

  // ─── Piece capabilities ─────────────────────────────────────────────────────
  // Pawn is absent: it is dispatched before this table is ever consulted.

  private val allowedShapes: Map[PieceType, Set[Shape]] = Map(
    PieceType.Rook -> Set(Shape.Horizontal, Shape.Vertical),
    PieceType.Bishop -> Set(Shape.Diagonal),
    PieceType.Queen -> Set(Shape.Horizontal, Shape.Vertical, Shape.Diagonal),
    PieceType.Knight -> Set(Shape.KnightLeap),
    PieceType.King -> Set(Shape.Horizontal, Shape.Vertical, Shape.Diagonal)
  )

  // ─── Validation entry point ─────────────────────────────────────────────────

  def validate(state: GameState, move: Move): Either[String, Unit] =
    state.board.get(move.from) match
      case None =>
        Left(s"No piece at ${move.from}")
      case Some(piece) if piece.color != state.activeColor =>
        Left(s"Cannot move ${piece.color} piece on ${state.activeColor}'s turn")
      case Some(piece) =>
        if isOccupiedBySameColor(state.board, move.to, piece.color) then
          Left(s"Cannot capture own piece at ${move.to}")
        else
          piece.pieceType match
            case PieceType.Pawn =>
              validatePawn(state.board, move, piece.color, state.enPassantTarget)
            case pt => validateByShape(state.board, move, pt)

  // ─── Shape-based pipeline (all non-pawn pieces) ─────────────────────────────

  private def validateByShape(board: Board, move: Move, pt: PieceType): Either[String, Unit] =
    val shape = classifyShape(move)
    if !allowedShapes(pt).contains(shape) then Left(s"Illegal move for $pt")
    else if pt == PieceType.King && chebyshevDistance(move) > 1 then
      Left("King can only move one square in any direction")
    else if shape != Shape.KnightLeap && !isPathClear(board, move) then
      Left(s"${pt} path is blocked")
    else Right(())

  // ─── Pawn (special: direction, double-step, diagonal-capture-only) ──────────

  private def validatePawn(
      board: Board,
      move: Move,
      color: Color,
      enPassantTarget: Option[Position]
  ): Either[String, Unit] =
    val direction = if color == Color.White then 1 else -1
    val startRank = if color == Color.White then 2 else 7
    val colDiff = move.to.col - move.from.col
    val rowDiff = move.to.row - move.from.row

    if colDiff == 0 then validatePawnForward(board, move, direction, startRank, rowDiff)
    else if Math.abs(colDiff) == 1 && rowDiff == direction then
      validatePawnCapture(board, move.to, enPassantTarget)
    else Left("Illegal pawn move")

  private def validatePawnForward(
      board: Board,
      move: Move,
      direction: Int,
      startRank: Int,
      rowDiff: Int
  ): Either[String, Unit] =
    if rowDiff == direction then
      if board.contains(move.to) then Left("Pawn cannot move forward: destination is occupied")
      else Right(())
    else if rowDiff == 2 * direction && move.from.row == startRank then
      val intermediate = Position(move.from.col, move.from.row + direction)
      if board.contains(intermediate) then Left("Pawn cannot move forward: path is blocked")
      else if board.contains(move.to) then Left("Pawn cannot move forward: destination is occupied")
      else Right(())
    else Left("Illegal pawn move")

  private def validatePawnCapture(
      board: Board,
      target: Position,
      enPassantTarget: Option[Position]
  ): Either[String, Unit] =
    if board.contains(target) || enPassantTarget.contains(target) then Right(())
    else Left("Pawn can only move diagonally to capture an enemy piece")

  // ─── Helpers ────────────────────────────────────────────────────────────────

  private def isOccupiedBySameColor(board: Board, pos: Position, color: Color): Boolean =
    board.get(pos).exists(_.color == color)

  private def chebyshevDistance(move: Move): Int =
    Math.max(Math.abs(move.to.col - move.from.col), Math.abs(move.to.row - move.from.row))

  private def isPathClear(board: Board, move: Move): Boolean =
    val colStep = math.signum(move.to.col - move.from.col)
    val rowStep = math.signum(move.to.row - move.from.row)
    val steps = chebyshevDistance(move)
    (1 until steps).forall: i =>
      val col = (move.from.col + colStep * i).toChar
      val row = move.from.row + rowStep * i
      !board.contains(Position(col, row))
