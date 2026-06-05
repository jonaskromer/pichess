package chess.model.rules

import zio.*

import chess.model.GameError
import chess.model.board.{Board, GameState, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}

object MoveValidator:

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
    validateSync(state, move) match
      case None      => ZIO.unit
      case Some(err) => ZIO.fail(err)

  /** Sync core of [[validate]]. Returns `Some(error)` when the move is
    * geometrically invalid (no piece at source, wrong color, captures
    * own piece, fails piece-specific rules), `None` when accepted.
    * Used by [[Game.tryApplyMoveCore]] and the batch [[legalDestinationsIndex]].
    */
  private[rules] def validateSync(state: GameState, move: Move): Option[GameError] =
    state.board.get(move.from) match
      case None =>
        invalidMove(s"No piece at ${move.from}")
      case Some(piece) if piece.color != state.activeColor =>
        invalidMove(s"${piece.color} piece cannot move on ${state.activeColor}'s turn")
      case Some(piece) if state.board.get(move.to).exists(_.color == piece.color) =>
        invalidMove(s"Cannot capture own piece at ${move.to}")
      case Some(piece) =>
        validatePieceRulesSync(state, move, piece)

  /** Boolean-returning variant of [[validateSync]] for the bot's
    * hot path. Mirrors the same rules but '''never''' constructs a
    * [[GameError]] — the per-call cost of allocating an exception
    * with stack trace dominated the search before. Used by
    * [[Game.applyMoveCoreSync]] for legality testing without
    * paying the message-construction cost.
    *
    * Callers that need a human-readable failure reason (e.g. the
    * gateway rejecting a user move) keep using [[validateSync]];
    * the bot, which only cares about valid-or-not, uses this. */
  def isLegalMoveSync(state: GameState, move: Move): Boolean =
    state.board.get(move.from) match
      case None => false
      case Some(piece) if piece.color != state.activeColor => false
      case Some(piece) if state.board.get(move.to).exists(_.color == piece.color) => false
      case Some(piece) => isLegalPieceRules(state, move, piece)

  private def isLegalPieceRules(
      state: GameState,
      move: Move,
      piece: Piece,
  ): Boolean =
    piece.pieceType match
      case PieceType.Pawn =>
        isLegalPawnMove(state.board, move, piece.color, state.enPassantTarget)
      case PieceType.King if isCastlingAttempt(move) =>
        isLegalCastling(state, move)
      case pt =>
        Ray.canReach(state.board, move.from, pt, move.to)

  /** Boolean form of [[validateCastlingSync]]. Same predicate
    * structure (rights / rook / blocked path / not in check /
    * transit-attack) but each failure returns `false` instead of
    * a `GameError`. */
  private def isLegalCastling(state: GameState, move: Move): Boolean =
    val color       = state.activeColor
    val rank        = if color == Color.White then 1 else 8
    val kingSide    = move.to.col > move.from.col
    val rookPos     = Position(if kingSide then 'h' else 'a', rank)
    val betweenCols = if kingSide then 'f' to 'g'        else 'b' to 'd'
    val transitCols = if kingSide then List('e','f','g') else List('e','d','c')
    castlingRight(state, color, kingSide) &&
      state.board.get(rookPos).contains(Piece(color, PieceType.Rook)) &&
      !pathBlocked(state, betweenCols, rank) &&
      !state.inCheck &&
      !transitsAttacked(state, transitCols, rank, color)

  /** Boolean form of [[validatePawnSync]]. Same case match but
    * each failure returns `false`. */
  private def isLegalPawnMove(
      board: Board,
      move: Move,
      color: Color,
      enPassantTarget: Option[Position]
  ): Boolean =
    val direction = if color == Color.White then 1 else -1
    val startRank = if color == Color.White then 2 else 7
    val colDiff   = move.to.col - move.from.col
    val rowDiff   = move.to.row - move.from.row
    (colDiff, rowDiff) match
      case (0, `direction`) =>
        !board.contains(move.to)
      case (0, d) if d == 2 * direction && move.from.row == startRank =>
        val intermediate = Position(move.from.col, move.from.row + direction)
        !board.contains(intermediate) && !board.contains(move.to)
      case (c, `direction`) if Math.abs(c) == 1 =>
        board.contains(move.to) || enPassantTarget.contains(move.to)
      case _ =>
        false

  private def validatePieceRulesSync(
      state: GameState,
      move: Move,
      piece: Piece
  ): Option[GameError] =
    piece.pieceType match
      case PieceType.Pawn =>
        validatePawnSync(state.board, move, piece.color, state.enPassantTarget)
      case PieceType.King if isCastlingAttempt(move) =>
        validateCastlingSync(state, move)
      case pt =>
        Option.when(!Ray.canReach(state.board, move.from, pt, move.to))(
          GameError.InvalidMove(s"$pt cannot move to ${move.to}")
        )

  // ─── Castling ──────────────────────────────────────────────────────────────

  private def isCastlingAttempt(move: Move): Boolean =
    Math.abs(move.to.col - move.from.col) == 2

  private def validateCastlingSync(
      state: GameState,
      move: Move
  ): Option[GameError] =
    val color       = state.activeColor
    val rank        = if color == Color.White then 1 else 8
    val kingSide    = move.to.col > move.from.col
    val rookPos     = Position(if kingSide then 'h' else 'a', rank)
    val betweenCols = if kingSide then 'f' to 'g'        else 'b' to 'd'
    val transitCols = if kingSide then List('e','f','g') else List('e','d','c')
    val hasRight    = castlingRight(state, color, kingSide)
    val rookOk      = state.board.get(rookPos).contains(Piece(color, PieceType.Rook))
    // Linear chain of fail-fast rules. Each branch short-circuits the
    // next so the cheap structural checks (rights, rook position) run
    // before the expensive attacked-square scan.
    if !hasRight     then invalidMove("Castling rights have been lost")
    else if !rookOk  then invalidMove("Rook is not on its starting square")
    else if pathBlocked(state, betweenCols, rank) then
                          invalidMove("Pieces are between king and rook")
    else if state.inCheck then invalidMove("Cannot castle while in check")
    else if transitsAttacked(state, transitCols, rank, color) then
      invalidMove("King passes through or lands on an attacked square")
    else None

  private def pathBlocked(state: GameState, cols: Seq[Char], rank: Int): Boolean =
    cols.exists(c => state.board.contains(Position(c, rank)))

  private def transitsAttacked(
      state: GameState, cols: Seq[Char], rank: Int, color: Color
  ): Boolean =
    cols.exists(c => isSquareAttacked(state.board, Position(c, rank), color))

  private def invalidMove(msg: String): Option[GameError] =
    Some(GameError.InvalidMove(msg))

  /** True iff the active-color side still has the named castling right. */
  private def castlingRight(state: GameState, color: Color, kingSide: Boolean): Boolean =
    val rights = state.castlingRights
    (color, kingSide) match
      case (Color.White, true)  => rights.whiteKingSide
      case (Color.White, false) => rights.whiteQueenSide
      case (Color.Black, true)  => rights.blackKingSide
      case (Color.Black, false) => rights.blackQueenSide

  // ─── Pawn ──────────────────────────────────────────────────────────────────

  private def validatePawnSync(
      board: Board,
      move: Move,
      color: Color,
      enPassantTarget: Option[Position]
  ): Option[GameError] =
    val direction = if color == Color.White then 1 else -1
    val startRank = if color == Color.White then 2 else 7
    val colDiff   = move.to.col - move.from.col
    val rowDiff   = move.to.row - move.from.row

    (colDiff, rowDiff) match
      case (0, `direction`) =>
        Option.when(board.contains(move.to))(
          GameError.InvalidMove("Pawn cannot move forward, destination is occupied")
        )
      case (0, d) if d == 2 * direction && move.from.row == startRank =>
        val intermediate = Position(move.from.col, move.from.row + direction)
        if board.contains(intermediate) then
          invalidMove("Pawn cannot move forward, path is blocked")
        else
          Option.when(board.contains(move.to))(
            GameError.InvalidMove("Pawn cannot move forward, destination is occupied")
          )
      case (c, `direction`) if Math.abs(c) == 1 =>
        Option.when(!(board.contains(move.to) || enPassantTarget.contains(move.to)))(
          GameError.InvalidMove("Pawn cannot capture, no enemy piece at destination")
        )
      case _ =>
        invalidMove(s"Pawn cannot move to ${move.to}")

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
      case None => ZIO.succeed(Nil)
      case Some(piece) if piece.color != state.activeColor => ZIO.succeed(Nil)
      case Some(piece) =>
        val candidates = candidateMoves(state, from, piece)
        ZIO
          .filter(candidates) { move =>
            Game.applyMoveCore(state, move).as(true).catchAll(_ => ZIO.succeed(false))
          }
          .map(_.map(_.to).distinct)

  /** Batched legal-destinations index — ZIO bench variant. Returns the
    * full per-piece map in a single effect so the gateway's annotation
    * cache can drop its per-piece loop.
    *
    * Bitboard-driven iteration of active-color sources (O(popCount) vs.
    * the old 64-square `board.toList.collect` scan), but each candidate
    * still rides through the full ZIO `applyMoveCore` pipeline.
    */
  def legalDestinationsIndex(
      state: GameState
  ): IO[GameError, Map[Position, List[Position]]] =
    val activeBb =
      if state.activeColor == Color.White then state.board.whitePieces.raw
      else state.board.blackPieces.raw
    val sources  = collectSources(activeBb)
    ZIO
      .foreach(sources) { from =>
        legalMovesFrom(state, from).map(from -> _)
      }
      .map(_.collect { case (k, v) if v.nonEmpty => k -> v }.toMap)

  /** Fully synchronous variant of [[legalDestinationsIndex]] —
    * mirrors the IO version's contract (returns the per-source
    * legal-destination map) but bypasses the ZIO scheduler.
    *
    * Used by the bot's `RulesAdapter` to avoid paying ~7% of
    * total search CPU in `FiberRuntime.runLoop` for what is
    * fundamentally a pure-CPU computation. Each candidate move
    * is filtered via [[Game.applyMoveCoreSync]] (also synchronous,
    * no GameError exception thrown). */
  def legalDestinationsIndexSync(
      state: GameState
  ): Map[Position, List[Position]] =
    val activeBb =
      if state.activeColor == Color.White then state.board.whitePieces.raw
      else state.board.blackPieces.raw
    val sources = collectSources(activeBb)
    val builder = Map.newBuilder[Position, List[Position]]
    val it = sources.iterator
    while it.hasNext do
      val from = it.next()
      val dests = legalMovesFromSync(state, from)
      if dests.nonEmpty then builder += (from -> dests)
    builder.result()

  /** Synchronous form of [[legalMovesFrom]]. Same behaviour:
    * filters candidate moves by attempting [[Game.applyMoveCoreSync]]
    * (which returns `None` for moves leaving the king in check, etc.)
    * and projects to destination squares.
    *
    * Inline iteration over the candidate `List` rather than the
    * old `ZIO.filter`; the per-element call overhead drops from a
    * `ZIO.flatMap` + `catchAll` + fibre allocation pair to a plain
    * `Option.isDefined` test. */
  def legalMovesFromSync(state: GameState, from: Position): List[Position] =
    state.board.get(from) match
      case None => Nil
      case Some(piece) if piece.color != state.activeColor => Nil
      case Some(piece) =>
        // Same `.distinct` semantics as the IO variant. The
        // current candidate generator never emits duplicate
        // destinations for a single piece (one promotion variant
        // per back-rank square, one move per ray endpoint), but
        // the `distinct` keeps the contract stable if richer
        // under-promotions land later.
        candidateMoves(state, from, piece).iterator
          .collect {
            case move if Game.applyMoveCoreSync(state, move).isDefined => move.to
          }
          .toList
          .distinct

  /** Two-stage variant of [[legalDestinationsIndex]] — partitions
    * the legal destinations into a `(captures, quiets)` pair so a
    * lazy move generator can iterate captures first, only paying
    * the quiet-move ordering / iteration cost when no α-β cutoff
    * fired in the captures stage.
    *
    * A "capture" is a move whose destination square holds an enemy
    * piece, OR a pawn move to the current en-passant target. Both
    * sub-indices are returned from a single
    * [[legalDestinationsIndex]] call — no extra rules-layer work.
    *
    * Same purity contract as [[legalDestinationsIndex]]: no
    * mutation, no callbacks. Returns empty sub-maps when the
    * partition is empty on a side. */
  def legalCapturesAndQuiets(
      state: GameState
  ): IO[GameError, (Map[Position, List[Position]], Map[Position, List[Position]])] =
    legalDestinationsIndex(state).map { index =>
      val captures = scala.collection.mutable.Map.empty[Position, List[Position]]
      val quiets   = scala.collection.mutable.Map.empty[Position, List[Position]]
      val it = index.iterator
      while it.hasNext do
        val (from, destinations) = it.next()
        val piece = state.board.get(from)
        val isPawn = piece.exists(_.pieceType == PieceType.Pawn)
        val (cap, qui) = destinations.partition { to =>
          state.board.contains(to) ||
          (isPawn && state.enPassantTarget.contains(to))
        }
        if cap.nonEmpty then captures.update(from, cap)
        if qui.nonEmpty then quiets.update(from, qui)
      (captures.toMap, quiets.toMap)
    }

  private def collectSources(bb: Long): List[Position] =
    val buf = scala.collection.mutable.ListBuffer.empty[Position]
    var rem = bb
    while rem != 0L do
      val idx = java.lang.Long.numberOfTrailingZeros(rem)
      rem &= rem - 1L
      buf += Position(('a' + (idx % 8)).toChar, (idx / 8) + 1)
    buf.toList

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
    ZIO.exists(pieces) { case (from, piece) =>
      val candidates = candidateMoves(state, from, piece)
      ZIO.exists(candidates) { move =>
        Game.applyMoveCore(state, move).as(true).catchAll(_ => ZIO.succeed(false))
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
