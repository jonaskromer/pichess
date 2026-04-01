package chess.model.rules

import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{Board, GameState, Move, Position}
import chess.model.GameError

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

  // ─── Validation entry point (monadic Either chain) ──────────────────────────
  //
  // Each step in the for-comprehension depends on the previous:
  //   1. resolve the piece at `from`       → need it for step 2
  //   2. check it belongs to active color  → need piece.color
  //   3. check destination isn't friendly  → need piece.color + move.to
  //   4. validate piece-specific rules     → need piece.pieceType
  //
  // If any step produces Left, the remaining steps are skipped (short-circuit).

  def validate(
      state: GameState,
      move: Move
  ): Either[GameError, Unit] =
    requirePieceAt(state, move.from)
      .flatMap(piece =>
        requireActiveColor(piece, state.activeColor)
          .flatMap(_ => requireNotFriendly(state.board, move.to, piece.color))
          .flatMap(_ => validatePieceRules(state, move, piece))
      )

  private def requirePieceAt(
      state: GameState,
      pos: Position
  ): Either[GameError, Piece] =
    state.board
      .get(pos)
      .toRight(GameError.InvalidMove(s"No piece at $pos"))

  private def requireActiveColor(
      piece: Piece,
      activeColor: Color
  ): Either[GameError, Unit] =
    Either.cond(
      piece.color == activeColor,
      (),
      GameError.InvalidMove(
        s"Cannot move ${piece.color} piece on ${activeColor}'s turn"
      )
    )

  private def requireNotFriendly(
      board: Board,
      pos: Position,
      color: Color
  ): Either[GameError, Unit] =
    Either.cond(
      !isOccupiedBySameColor(board, pos, color),
      (),
      GameError.InvalidMove(s"Cannot capture own piece at $pos")
    )

  private def validatePieceRules(
      state: GameState,
      move: Move,
      piece: Piece
  ): Either[GameError, Unit] =
    piece.pieceType match
      case PieceType.Pawn =>
        validatePawn(state.board, move, piece.color, state.enPassantTarget)
      case pt => validateByShape(state.board, move, pt)

  // ─── Shape-based pipeline (all non-pawn pieces) ─────────────────────────────

  private def validateByShape(
      board: Board,
      move: Move,
      pt: PieceType
  ): Either[GameError, Unit] =
    requireAllowedShape(move, pt)
      .flatMap(_ => requireKingDistance(move, pt))
      .flatMap(_ => requireClearPath(board, move, pt))

  private def requireAllowedShape(
      move: Move,
      pt: PieceType
  ): Either[GameError, Unit] =
    Either.cond(
      allowedShapes(pt).contains(classifyShape(move)),
      (),
      GameError.InvalidMove(s"Illegal move for $pt")
    )

  private def requireKingDistance(
      move: Move,
      pt: PieceType
  ): Either[GameError, Unit] =
    Either.cond(
      pt != PieceType.King || chebyshevDistance(move) <= 1,
      (),
      GameError.InvalidMove("King can only move one square in any direction")
    )

  private def requireClearPath(
      board: Board,
      move: Move,
      pt: PieceType
  ): Either[GameError, Unit] =
    Either.cond(
      classifyShape(move) == Shape.KnightLeap || isPathClear(board, move),
      (),
      GameError.InvalidMove(s"${pt} path is blocked")
    )

  // ─── Pawn (special: direction, double-step, diagonal-capture-only) ──────────

  private def validatePawn(
      board: Board,
      move: Move,
      color: Color,
      enPassantTarget: Option[Position]
  ): Either[GameError, Unit] =
    val direction = if color == Color.White then 1 else -1
    val startRank = if color == Color.White then 2 else 7
    val colDiff = move.to.col - move.from.col
    val rowDiff = move.to.row - move.from.row

    if colDiff == 0 then
      validatePawnForward(board, move, direction, startRank, rowDiff)
    else if Math.abs(colDiff) == 1 && rowDiff == direction then
      validatePawnCapture(board, move.to, enPassantTarget)
    else Left(GameError.InvalidMove("Illegal pawn move"))

  private def validatePawnForward(
      board: Board,
      move: Move,
      direction: Int,
      startRank: Int,
      rowDiff: Int
  ): Either[GameError, Unit] =
    if rowDiff == direction then
      Either.cond(
        !board.contains(move.to),
        (),
        GameError.InvalidMove(
          "Pawn cannot move forward: destination is occupied"
        )
      )
    else if rowDiff == 2 * direction && move.from.row == startRank then
      val intermediate = Position(move.from.col, move.from.row + direction)
      Either
        .cond(
          !board.contains(intermediate),
          (),
          GameError.InvalidMove("Pawn cannot move forward: path is blocked")
        )
        .flatMap(_ =>
          Either.cond(
            !board.contains(move.to),
            (),
            GameError.InvalidMove(
              "Pawn cannot move forward: destination is occupied"
            )
          )
        )
    else Left(GameError.InvalidMove("Illegal pawn move"))

  private def validatePawnCapture(
      board: Board,
      target: Position,
      enPassantTarget: Option[Position]
  ): Either[GameError, Unit] =
    Either.cond(
      board.contains(target) || enPassantTarget.contains(target),
      (),
      GameError.InvalidMove(
        "Pawn can only move diagonally to capture an enemy piece"
      )
    )

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
