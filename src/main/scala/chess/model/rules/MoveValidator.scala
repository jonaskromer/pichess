package chess.model.rules

import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{Board, GameState, Move, Position}
import chess.model.GameError
import zio.*

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

  // ─── Validation entry point ────────────────────────────────────────────────
  //
  // Each step in the for-comprehension depends on the previous:
  //   1. resolve the piece at `from`       → need it for step 2
  //   2. check it belongs to active color  → need piece.color
  //   3. check destination isn't friendly  → need piece.color + move.to
  //   4. validate piece-specific rules     → need piece.pieceType
  //
  // If any step fails, the remaining steps are skipped (short-circuit).

  def validate(
      state: GameState,
      move: Move
  ): IO[GameError, Unit] =
    requirePieceAt(state, move.from).flatMap { piece =>
      requireActiveColor(piece, state.activeColor) *>
        requireNotFriendly(state.board, move.to, piece.color) *>
        validatePieceRules(state, move, piece)
    }

  private def requirePieceAt(
      state: GameState,
      pos: Position
  ): IO[GameError, Piece] =
    ZIO
      .fromOption(state.board.get(pos))
      .orElseFail(GameError.InvalidMove(s"No piece at $pos"))

  private def requireActiveColor(
      piece: Piece,
      activeColor: Color
  ): IO[GameError, Unit] =
    ZIO.unless(piece.color == activeColor)(
      ZIO.fail(
        GameError.InvalidMove(
          s"Cannot move ${piece.color} piece on ${activeColor}'s turn"
        )
      )
    ).unit

  private def requireNotFriendly(
      board: Board,
      pos: Position,
      color: Color
  ): IO[GameError, Unit] =
    ZIO.when(isOccupiedBySameColor(board, pos, color))(
      ZIO.fail(GameError.InvalidMove(s"Cannot capture own piece at $pos"))
    ).unit

  private def validatePieceRules(
      state: GameState,
      move: Move,
      piece: Piece
  ): IO[GameError, Unit] =
    piece.pieceType match
      case PieceType.Pawn =>
        validatePawn(state.board, move, piece.color, state.enPassantTarget)
      case pt => validateByShape(state.board, move, pt)

  // ─── Shape-based pipeline (all non-pawn pieces) ─────────────────────────────

  private def validateByShape(
      board: Board,
      move: Move,
      pt: PieceType
  ): IO[GameError, Unit] =
    requireAllowedShape(move, pt) *>
      requireKingDistance(move, pt) *>
      requireClearPath(board, move, pt)

  private def requireAllowedShape(
      move: Move,
      pt: PieceType
  ): IO[GameError, Unit] =
    ZIO.unless(allowedShapes(pt).contains(classifyShape(move)))(
      ZIO.fail(GameError.InvalidMove(s"Illegal move for $pt"))
    ).unit

  private def requireKingDistance(
      move: Move,
      pt: PieceType
  ): IO[GameError, Unit] =
    ZIO.when(pt == PieceType.King && chebyshevDistance(move) > 1)(
      ZIO.fail(
        GameError.InvalidMove("King can only move one square in any direction")
      )
    ).unit

  private def requireClearPath(
      board: Board,
      move: Move,
      pt: PieceType
  ): IO[GameError, Unit] =
    ZIO.unless(
      classifyShape(move) == Shape.KnightLeap || isPathClear(board, move)
    )(
      ZIO.fail(GameError.InvalidMove(s"${pt} path is blocked"))
    ).unit

  // ─── Pawn (special: direction, double-step, diagonal-capture-only) ──────────

  private def validatePawn(
      board: Board,
      move: Move,
      color: Color,
      enPassantTarget: Option[Position]
  ): IO[GameError, Unit] =
    val direction = if color == Color.White then 1 else -1
    val startRank = if color == Color.White then 2 else 7
    val colDiff = move.to.col - move.from.col
    val rowDiff = move.to.row - move.from.row

    if colDiff == 0 then
      validatePawnForward(board, move, direction, startRank, rowDiff)
    else if Math.abs(colDiff) == 1 && rowDiff == direction then
      validatePawnCapture(board, move.to, enPassantTarget)
    else ZIO.fail(GameError.InvalidMove("Illegal pawn move"))

  private def validatePawnForward(
      board: Board,
      move: Move,
      direction: Int,
      startRank: Int,
      rowDiff: Int
  ): IO[GameError, Unit] =
    if rowDiff == direction then
      ZIO.when(board.contains(move.to))(
        ZIO.fail(
          GameError.InvalidMove(
            "Pawn cannot move forward: destination is occupied"
          )
        )
      ).unit
    else if rowDiff == 2 * direction && move.from.row == startRank then
      val intermediate = Position(move.from.col, move.from.row + direction)
      ZIO
        .when(board.contains(intermediate))(
          ZIO.fail(
            GameError.InvalidMove("Pawn cannot move forward: path is blocked")
          )
        )
        .unit *>
        ZIO
          .when(board.contains(move.to))(
            ZIO.fail(
              GameError.InvalidMove(
                "Pawn cannot move forward: destination is occupied"
              )
            )
          )
          .unit
    else ZIO.fail(GameError.InvalidMove("Illegal pawn move"))

  private def validatePawnCapture(
      board: Board,
      target: Position,
      enPassantTarget: Option[Position]
  ): IO[GameError, Unit] =
    ZIO.unless(board.contains(target) || enPassantTarget.contains(target))(
      ZIO.fail(
        GameError.InvalidMove(
          "Pawn can only move diagonally to capture an enemy piece"
        )
      )
    ).unit

  // ─── Helpers ────────────────────────────────────────────────────────────────

  private def isOccupiedBySameColor(
      board: Board,
      pos: Position,
      color: Color
  ): Boolean =
    board.get(pos).exists(_.color == color)

  private def chebyshevDistance(move: Move): Int =
    Math.max(
      Math.abs(move.to.col - move.from.col),
      Math.abs(move.to.row - move.from.row)
    )

  private def isPathClear(board: Board, move: Move): Boolean =
    val colStep = math.signum(move.to.col - move.from.col)
    val rowStep = math.signum(move.to.row - move.from.row)
    val steps = chebyshevDistance(move)
    (1 until steps).forall: i =>
      val col = (move.from.col + colStep * i).toChar
      val row = move.from.row + rowStep * i
      !board.contains(Position(col, row))
