package chess.model.rules

import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{Board, GameState, Move, Position}
import chess.model.GameError
import zio.*

object MoveValidator:

  private def guard(cond: Boolean)(msg: String): IO[GameError, Unit] =
    ZIO.when(!cond)(ZIO.fail(GameError.InvalidMove(msg))).unit

  // ─── Validation entry point ────────────────────────────────────────────────

  def validate(state: GameState, move: Move): IO[GameError, Unit] =
    ZIO
      .fromOption(state.board.get(move.from))
      .orElseFail(GameError.InvalidMove(s"No piece at ${move.from}"))
      .flatMap { piece =>
        guard(piece.color == state.activeColor)(
          s"${piece.color} piece cannot move on ${state.activeColor}'s turn"
        ) *> guard(!state.board.get(move.to).exists(_.color == piece.color))(
          s"Cannot capture own piece at ${move.to}"
        ) *> validatePieceRules(state, move, piece)
      }

  private def validatePieceRules(
      state: GameState,
      move: Move,
      piece: Piece
  ): IO[GameError, Unit] =
    piece.pieceType match
      case PieceType.Pawn =>
        validatePawn(state.board, move, piece.color, state.enPassantTarget)
      case pt =>
        guard(Ray.canReach(state.board, move.from, pt, move.to))(
          s"$pt cannot move to ${move.to}"
        )

  // ─── Pawn ──────────────────────────────────────────────────────────────────

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

    (colDiff, rowDiff) match
      case (0, `direction`) =>
        guard(!board.contains(move.to))(
          "Pawn cannot move forward, destination is occupied"
        )
      case (0, d) if d == 2 * direction && move.from.row == startRank =>
        val intermediate = Position(move.from.col, move.from.row + direction)
        guard(!board.contains(intermediate))(
          "Pawn cannot move forward, path is blocked"
        ) *> guard(!board.contains(move.to))(
          "Pawn cannot move forward, destination is occupied"
        )
      case (c, `direction`) if Math.abs(c) == 1 =>
        guard(board.contains(move.to) || enPassantTarget.contains(move.to))(
          "Pawn cannot capture, no enemy piece at destination"
        )
      case _ =>
        ZIO.fail(GameError.InvalidMove(s"Pawn cannot move to ${move.to}"))

  // ─── Check detection ───────────────────────────────────────────────────────

  def isInCheck(board: Board, color: Color): Boolean =
    board.collectFirst { case (pos, Piece(`color`, PieceType.King)) => pos } match
      case None => false
      case Some(kingPos) =>
        board.exists { case (pos, piece) =>
          piece.color != color && canAttack(board, pos, piece, kingPos)
        }

  private def canAttack(
      board: Board,
      from: Position,
      piece: Piece,
      target: Position
  ): Boolean =
    piece.pieceType match
      case PieceType.Pawn =>
        val direction = if piece.color == Color.White then 1 else -1
        Math.abs(target.col - from.col) == 1 && (target.row - from.row) == direction
      case pt =>
        Ray.canReach(board, from, pt, target)
