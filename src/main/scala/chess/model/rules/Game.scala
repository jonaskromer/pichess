package chess.model.rules

import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{
  Board,
  CastlingRights,
  DrawReason,
  GameState,
  GameStatus,
  Move,
  Position
}
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

  /** Applies a move without detecting checkmate/stalemate. Used by
    * [[MoveValidator.hasLegalMove]] to avoid infinite recursion.
    */
  private[rules] def applyMoveCore(
      state: GameState,
      move: Move
  ): IO[GameError, GameState] =
    for
      _ <- ZIO.when(state.status != GameStatus.Playing)(
        ZIO.fail(GameError.InvalidMove("Game is over"))
      )
      _ <- MoveValidator.validate(state, move)
      piece = state.board(move.from)
      _ <- validatePromotion(piece, move)
      movedPiece = promotedPiece(piece, move)
      newBoard = updatedBoard(state, move, movedPiece)
      _ <- ZIO.when(MoveValidator.isInCheck(newBoard, state.activeColor))(
        ZIO.fail(GameError.InvalidMove("King cannot be left in check"))
      )
      opponentInCheck = MoveValidator.isInCheck(
        newBoard,
        state.activeColor.opposite
      )
      isCapture = state.board.contains(move.to) || isEnPassantCapture(
        state,
        move,
        piece
      )
      isPawnMove = piece.pieceType == PieceType.Pawn
      newHalfmove =
        if isPawnMove || isCapture then 0 else state.halfmoveClock + 1
      newFullmove =
        if state.activeColor == Color.Black then state.fullmoveNumber + 1
        else state.fullmoveNumber
    yield GameState(
      board = newBoard,
      activeColor = state.activeColor.opposite,
      enPassantTarget = nextEnPassantTarget(move, piece),
      inCheck = opponentInCheck,
      castlingRights = updatedCastlingRights(state, move),
      halfmoveClock = newHalfmove,
      fullmoveNumber = newFullmove
    )

  private def isInsufficientMaterial(board: Board): Boolean =
    val pieces = board.values.toList
    val nonKings = pieces.filterNot(_.pieceType == PieceType.King)
    nonKings match
      case Nil => true // K vs K
      case List(p) =>
        p.pieceType == PieceType.Bishop || p.pieceType == PieceType.Knight
      case List(a, b)
          if a.pieceType == PieceType.Bishop
            && b.pieceType == PieceType.Bishop
            && a.color != b.color =>
        val posA = board.collectFirst { case (pos, p) if p == a => pos }.get
        val posB = board.collectFirst { case (pos, p) if p == b => pos }.get
        squareColor(posA) == squareColor(posB)
      case _ => false

  private def squareColor(pos: Position): Int =
    (pos.col - 'a' + pos.row) % 2

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
