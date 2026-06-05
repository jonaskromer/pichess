package chess.model.rules

import zio.*

import chess.model.GameError
import chess.model.board.{
  Board,
  CastlingRights,
  DrawReason,
  GameState,
  GameStatus,
  Move,
  Position
}
import chess.model.piece.{Color, Piece, PieceType}

object Game:
  private val promotionPieces: Set[PieceType] =
    Set(PieceType.Queen, PieceType.Rook, PieceType.Bishop, PieceType.Knight)

  /** Apply a move to `state`, returning the resulting [[GameState]].
    *
    * Orchestrates validation, board mutation, and status detection. The
    * returned state reflects every consequence of the move: updated board,
    * flipped active color, revoked castling rights, new or cleared en-passant
    * target, incremented/reset halfmove clock, fullmove number, `inCheck` flag,
    * and a resolved [[GameStatus]]:
    *
    *   - [[GameStatus.Checkmate]] if the opponent has no legal reply while in
    *     check
    *   - [[GameStatus.Draw]] with the appropriate [[DrawReason]] for stalemate
    *     or insufficient material
    *   - [[GameStatus.Playing]] otherwise
    *
    * Fails with [[GameError.InvalidMove]] when:
    *   - the game is already over (`state.status.isOver`)
    *   - no piece exists at `move.from`
    *   - the piece belongs to the non-active color
    *   - the move would capture an own-color piece
    *   - the piece-specific rules reject the move (including castling path
    *     checks and en-passant validity)
    *   - the move would leave the active color's king in check
    *   - promotion is missing, excess, or to an invalid piece type
    *
    * Threefold/fivefold repetition detection is **not** performed here — that
    * requires history, which lives in [[chess.model.GameSnapshot]] and is
    * handled by [[chess.controller.GameController.makeMove]].
    */
  def applyMove(
      state: GameState,
      move: Move
  ): IO[GameError, GameState] =
    for
      newState <- applyMoveCore(state, move)
      status <-
        if isInsufficientMaterial(newState.board) then
          ZIO.succeed(GameStatus.Draw(DrawReason.InsufficientMaterial))
        else
          MoveValidator.hasLegalMove(newState).map { hasMove =>
            if !hasMove && newState.inCheck then
              GameStatus.Checkmate(state.activeColor)
            else if !hasMove then GameStatus.Draw(DrawReason.Stalemate)
            else GameStatus.Playing
          }
    yield newState.copy(status = status)

  /** Search-friendly variant of [[applyMove]] for tight inner-loop
    * use. Same validation as [[applyMove]] — piece rules, king-
    * safety, promotion — but '''skips''' the post-apply
    * `isInsufficientMaterial` + `MoveValidator.hasLegalMove`
    * status-detection step. The returned state's `status` field is
    * therefore always `GameStatus.Playing`; callers that need
    * terminal detection do it themselves (the bot's search uses
    * its own `legalMoves.isEmpty` check at every node).
    *
    * Profile evidence: `applyMove` was 34% of chess CPU at depth 4
    * in the bot search because every applied move triggered a
    * second full legal-move generation via `hasLegalMove`. Routing
    * the bot through this lighter entry point eliminates that
    * doubled work. */
  def applyMoveForSearch(
      state: GameState,
      move: Move
  ): IO[GameError, GameState] =
    applyMoveCore(state, move)

  /** Applies a move without detecting checkmate/stalemate. Used by
    * [[MoveValidator.hasLegalMove]] to avoid infinite recursion.
    *
    * Phase 3 ZIO-bench variant: pure IO for-comp, no internal sync
    * Either pathway. Each `ZIO.when` step costs ~30ns of scheduling;
    * the batch sees this ~160× per annotation rebuild.
    */
  private[rules] def applyMoveCore(
      state: GameState,
      move: Move
  ): IO[GameError, GameState] =
    for
      _          <- ZIO.fail(GameError.InvalidMove("Game is over"))
                      .when(state.status.isOver)
      _          <- MoveValidator.validate(state, move)
      piece       = state.board(move.from)
      _          <- validatePromotion(piece, move)
      newBoard    = updatedBoard(state, move, promotedPiece(piece, move))
      _          <- ZIO.fail(GameError.InvalidMove("King cannot be left in check"))
                      .when(MoveValidator.isInCheck(newBoard, state.activeColor))
    yield buildPostMoveState(state, move, piece, newBoard)

  /** Assemble the post-move [[GameState]] after every legality check has
    * passed. Pure plumbing — extracted from the for-comp so the rule
    * sequencing stays readable. */
  private def buildPostMoveState(
      state: GameState,
      move: Move,
      piece: Piece,
      newBoard: Board
  ): GameState =
    val opponentInCheck =
      MoveValidator.isInCheck(newBoard, state.activeColor.opposite)
    val isCapture =
      state.board.contains(move.to) ||
        isEnPassantCapture(state, move, piece)
    val isPawnMove   = piece.pieceType == PieceType.Pawn
    val newHalfmove  = if isPawnMove || isCapture then 0
                       else state.halfmoveClock + 1
    val newFullmove  =
      if state.activeColor == Color.Black then state.fullmoveNumber + 1
      else state.fullmoveNumber
    GameState(
      board           = newBoard,
      activeColor     = state.activeColor.opposite,
      enPassantTarget = nextEnPassantTarget(move, piece),
      inCheck         = opponentInCheck,
      castlingRights  = updatedCastlingRights(state, move),
      halfmoveClock   = newHalfmove,
      fullmoveNumber  = newFullmove
    )

  private def isInsufficientMaterial(board: Board): Boolean =
    val nonKings = board.toList.collect {
      case (pos, piece) if piece.pieceType != PieceType.King => (pos, piece)
    }
    nonKings match
      case Nil => true // K vs K
      case List((_, p)) =>
        p.pieceType == PieceType.Bishop || p.pieceType == PieceType.Knight
      case List((posA, a), (posB, b))
          if a.pieceType == PieceType.Bishop
            && b.pieceType == PieceType.Bishop
            && a.color != b.color =>
        squareColor(posA) == squareColor(posB)
      case _ => false

  private def squareColor(pos: Position): Int =
    (pos.col - 'a' + pos.row) % 2

  private def isPromotionRank(piece: Piece, row: Int): Boolean =
    piece.pieceType == PieceType.Pawn &&
      ((piece.color == Color.White && row == 8) ||
        (piece.color == Color.Black && row == 1))

  private def validatePromotion(piece: Piece, move: Move): IO[GameError, Unit] =
    val reachesBackRank = isPromotionRank(piece, move.to.row)
    (move.promotion, reachesBackRank) match
      case (Some(_), false) =>
        ZIO.fail(GameError.InvalidMove("Pawn cannot promote unless it reaches the back rank"))
      case (Some(pt), true) if !promotionPieces.contains(pt) =>
        ZIO.fail(GameError.InvalidMove("Pawn must promote to Queen, Rook, Bishop, or Knight"))
      case (None, true) =>
        ZIO.fail(GameError.InvalidMove("Pawn must promote when reaching the back rank (e.g. e8=Q)"))
      case _ =>
        ZIO.unit

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
