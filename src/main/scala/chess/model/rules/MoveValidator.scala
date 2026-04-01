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
      case PieceType.King if isCastlingAttempt(move) =>
        validateCastling(state, move)
      case pt =>
        guard(Ray.canReach(state.board, move.from, pt, move.to))(
          s"$pt cannot move to ${move.to}"
        )

  // ─── Castling ──────────────────────────────────────────────────────────────

  private def isCastlingAttempt(move: Move): Boolean =
    Math.abs(move.to.col - move.from.col) == 2

  private def validateCastling(
      state: GameState,
      move: Move
  ): IO[GameError, Unit] =
    val color = state.activeColor
    val rank = if color == Color.White then 1 else 8
    val kingSide = move.to.col > move.from.col
    val rookCol = if kingSide then 'h' else 'a'
    val rookPos = Position(rookCol, rank)

    val hasRight =
      if color == Color.White then
        if kingSide then state.castlingRights.whiteKingSide
        else state.castlingRights.whiteQueenSide
      else if kingSide then state.castlingRights.blackKingSide
      else state.castlingRights.blackQueenSide

    val betweenCols =
      if kingSide then ('f' to 'g')
      else ('b' to 'd')

    val pathClear =
      betweenCols.forall(c => !state.board.contains(Position(c, rank)))

    val transitCols =
      if kingSide then List('e', 'f', 'g')
      else List('e', 'd', 'c')

    guard(hasRight)("Castling rights have been lost") *>
      guard(state.board.get(rookPos).contains(Piece(color, PieceType.Rook)))(
        "Rook is not on its starting square"
      ) *>
      guard(pathClear)("Pieces are between king and rook") *>
      guard(!state.inCheck)("Cannot castle while in check") *>
      guard(
        transitCols.forall(c =>
          !isSquareAttacked(state.board, Position(c, rank), color)
        )
      )("King passes through or lands on an attacked square")

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

  def isSquareAttacked(
      board: Board,
      square: Position,
      byOpponentOf: Color
  ): Boolean =
    board.exists { case (pos, piece) =>
      piece.color != byOpponentOf && canAttack(board, pos, piece, square)
    }

  def isInCheck(board: Board, color: Color): Boolean =
    board.collectFirst { case (pos, Piece(`color`, PieceType.King)) =>
      pos
    } match
      case None => false
      case Some(kingPos) =>
        isSquareAttacked(board, kingPos, color)

  private def canAttack(
      board: Board,
      from: Position,
      piece: Piece,
      target: Position
  ): Boolean =
    piece.pieceType match
      case PieceType.Pawn =>
        val direction = if piece.color == Color.White then 1 else -1
        Math.abs(
          target.col - from.col
        ) == 1 && (target.row - from.row) == direction
      case pt =>
        Ray.canReach(board, from, pt, target)

  // ─── Legal move detection ─────────────────────────────────────────────────

  def hasLegalMove(state: GameState): IO[GameError, Boolean] =
    val color = state.activeColor
    val pieces = state.board.toList.collect {
      case (pos, piece) if piece.color == color => (pos, piece)
    }
    ZIO
      .exists(pieces) { case (from, piece) =>
        val candidates = candidateMoves(state, from, piece)
        ZIO.exists(candidates) { move =>
          Game
            .applyMove(state, move)
            .as(true)
            .catchAll(_ => ZIO.succeed(false))
        }
      }

  private def candidateMoves(
      state: GameState,
      from: Position,
      piece: Piece
  ): List[Move] =
    piece.pieceType match
      case PieceType.Pawn => pawnCandidates(from, piece.color, state)
      case PieceType.King => kingCandidates(state, from, piece)
      case pt =>
        Ray
          .table(pt)
          .flatMap(ray => Ray.walk(state.board, from, ray))
          .map(to => Move(from, to))

  private def pawnCandidates(
      from: Position,
      color: Color,
      state: GameState
  ): List[Move] =
    val direction = if color == Color.White then 1 else -1
    val startRank = if color == Color.White then 2 else 7
    val promoRank = if color == Color.White then 8 else 1

    val forward1 = Option
      .when(from.row + direction >= 1 && from.row + direction <= 8)(
        Position(from.col, from.row + direction)
      )
      .toList
    val forward2 = Option
      .when(from.row == startRank)(
        Position(from.col, from.row + 2 * direction)
      )
      .toList
    val captures = List(-1, 1).flatMap { dc =>
      val c = from.col + dc
      val r = from.row + direction
      Option
        .when(c >= 'a' && c <= 'h' && r >= 1 && r <= 8)(Position(c.toChar, r))
    }
    val targets = forward1 ++ forward2 ++ captures
    targets.flatMap { to =>
      if to.row == promoRank then List(Move(from, to, Some(PieceType.Queen)))
      else List(Move(from, to))
    }

  private def kingCandidates(
      state: GameState,
      from: Position,
      piece: Piece
  ): List[Move] =
    val normalMoves = Ray
      .table(PieceType.King)
      .flatMap(ray => Ray.walk(state.board, from, ray))
      .map(to => Move(from, to))
    val rank = if piece.color == Color.White then 1 else 8
    val castlingMoves = List(
      Move(from, Position('g', rank)),
      Move(from, Position('c', rank))
    )
    normalMoves ++ castlingMoves
