package chess.model.rules

import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{Board, GameState, Move, Position}
import chess.model.GameError
import zio.*

object MoveValidator:

  private def guard(cond: Boolean)(msg: String): IO[GameError, Unit] =
    ZIO.when(!cond)(ZIO.fail(GameError.InvalidMove(msg))).unit

  // ─── Validation entry point ────────────────────────────────────────────────

  /** Validate that a move is legal from `state` per the piece-specific rules.
    *
    * Checks, in order:
    *   1. A piece exists at `move.from`. 2. That piece belongs to
    *      `state.activeColor`. 3. The destination is not occupied by a
    *      same-colored piece. 4. The piece-specific movement rules: pawn pushes
    *      / captures / en passant; king castling (path clear, rook present, not
    *      through an attacked square); sliding/leaping geometry for every other
    *      piece (via [[Ray.canReach]]).
    *
    * Does '''not''' check:
    *   - whether the move leaves the active king in check (this is done inside
    *     [[Game.applyMoveCore]] after the move is materialised)
    *   - whether the game is already over
    *   - promotion correctness (also handled by [[Game.applyMoveCore]])
    *   - repetition / 50-move draw conditions (those live at the controller
    *     layer, which carries history)
    *
    * Fails with [[GameError.InvalidMove]] carrying a caller-facing message.
    */
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

  // ─── Check detection (bitboard-native) ───────────────────────────────────
  //
  // Phase 2 of the bitboard migration. Every predicate is now O(1)-ish
  // (a handful of bit-AND/OR/shift ops per call) instead of iterating
  // the whole board:
  //   - king position comes from `board.kingW`/`kingB`.lowestBitIdx —
  //     no `collectFirst` scan.
  //   - opponent attackers are read directly from the per-piece
  //     bitboards, intersected with the precomputed leaper tables in
  //     [[BitboardAttacks]] for knight/king/pawn and with
  //     bitboardAttacks-style ray walks for bishop/rook/queen.
  //
  // The bench delta vs Phase 1 is large because `isInCheck` is the
  // inner loop of `legalMovesFrom` (via `applyMoveCore`'s king-safety
  // filter), which dominates gateway annotation-rebuild CPU.

  def isSquareAttacked(
      board: Board,
      square: Position,
      byOpponentOf: Color
  ): Boolean =
    val sqIdx     = square.squareIdx
    val attacker  = if byOpponentOf == Color.White then Color.Black else Color.White
    attackerBitboard(board, sqIdx, attacker) != 0L

  def isInCheck(board: Board, color: Color): Boolean =
    val kingBb =
      if color == Color.White then board.kingW.raw else board.kingB.raw
    if kingBb == 0L then false
    else
      val kingIdx = java.lang.Long.numberOfTrailingZeros(kingBb)
      val attacker = if color == Color.White then Color.Black else Color.White
      attackerBitboard(board, kingIdx, attacker) != 0L

  /** Bitboard of every piece of `byColor` that attacks `target`. The
    * caller may take `.popCount == 0` for "is attacked", iterate set
    * bits for "list attackers", or AND with a piece-type bitboard for
    * type-specific queries.
    */
  private def attackerBitboard(
      board: Board,
      target: Int,
      byColor: Color
  ): Long =
    val occ = board.occupancy.raw
    if byColor == Color.White then
      val pawns   = board.pawnsW.raw   & BitboardAttacks.whitePawnAttackersOf(target)
      val knights = board.knightsW.raw & BitboardAttacks.knightAttacks(target)
      val king    = board.kingW.raw    & BitboardAttacks.kingAttacks(target)
      val diag    = (board.bishopsW.raw | board.queensW.raw) &
                    BitboardAttacks.bishopAttacks(target, occ)
      val ortho   = (board.rooksW.raw | board.queensW.raw) &
                    BitboardAttacks.rookAttacks(target, occ)
      pawns | knights | king | diag | ortho
    else
      val pawns   = board.pawnsB.raw   & BitboardAttacks.blackPawnAttackersOf(target)
      val knights = board.knightsB.raw & BitboardAttacks.knightAttacks(target)
      val king    = board.kingB.raw    & BitboardAttacks.kingAttacks(target)
      val diag    = (board.bishopsB.raw | board.queensB.raw) &
                    BitboardAttacks.bishopAttacks(target, occ)
      val ortho   = (board.rooksB.raw | board.queensB.raw) &
                    BitboardAttacks.rookAttacks(target, occ)
      pawns | knights | king | diag | ortho

  // ─── Legal move detection ─────────────────────────────────────────────────

  /** Every square the piece at `from` can legally move to from `state`,
    * with king-safety filtering applied (i.e. moves that would leave the
    * active king in check are excluded). Returns `Nil` when:
    *   - there's no piece at `from`,
    *   - the piece doesn't belong to the active color,
    *   - the piece has no legal destination (pinned, blocked, …).
    *
    * Used by the gateway's `/legal-moves` annotation endpoint to power the
    * web-ui's move-preview overlay. Pawn promotions collapse to a single
    * destination — the UI doesn't need to know which promotion piece.
    */
  def legalMovesFrom(
      state: GameState,
      from: Position
  ): IO[GameError, List[Position]] =
    state.board.get(from) match
      case None =>
        ZIO.succeed(Nil)
      case Some(piece) if piece.color != state.activeColor =>
        ZIO.succeed(Nil)
      case Some(piece) =>
        val candidates = candidateMoves(state, from, piece)
        ZIO
          .filter(candidates) { move =>
            // applyMoveCore runs the same legality + king-safety check the
            // controller would. A failure means the move is illegal for any
            // reason (geometry, leaves king in check, castling restriction,
            // etc.) — squelch it to a `false` for the filter.
            Game
              .applyMoveCore(state, move)
              .as(true)
              .catchAll(_ => ZIO.succeed(false))
          }
          // `candidates` for promotions emits one Move per piece type; the
          // destinations dedupe naturally via `.distinct` here.
          .map(_.map(_.to).distinct)

  /** Every square holding a piece of `byColor` that attacks `square`.
    * Used by the gateway's `/attackers` annotation endpoint to render a
    * "who's attacking this piece" overlay in the web-ui. Pure — only the
    * board geometry is consulted, not the rest of the game state.
    *
    * `byColor` is the *attacker's* color (e.g. pass `Color.Black` to find
    * the black pieces threatening a white piece). This deliberately mirrors
    * the way callers think about it ("who is attacking me?"), unlike
    * [[isSquareAttacked]] which takes the defender's color.
    */
  def attackersOf(
      board: Board,
      square: Position,
      byColor: Color
  ): List[Position] =
    // Same bitboard machinery as `isInCheck` / `isSquareAttacked` —
    // the attacker bitboard is the bits of the squares that attack
    // `target`. Walking the set bits and reconstructing Position
    // values is the only iteration cost; for the typical "two or
    // three attackers" case this is ~10× faster than the previous
    // `board.toList.collect` full-board scan.
    val attackers = attackerBitboard(board, square.squareIdx, byColor)
    if attackers == 0L then Nil
    else
      val buf = scala.collection.mutable.ListBuffer.empty[Position]
      var rem = attackers
      while rem != 0L do
        val idx = java.lang.Long.numberOfTrailingZeros(rem)
        rem &= rem - 1L
        buf += Position(('a' + (idx % 8)).toChar, (idx / 8) + 1)
      buf.toList

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
            .applyMoveCore(state, move)
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
