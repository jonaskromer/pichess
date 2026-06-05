package chess.bot.engine

import zio.{UIO, ZIO}

import chess.bot.engine.internal.RulesAdapter
import chess.model.board.{GameState, Move}
import chess.model.piece.{Color, PieceType}
import chess.model.rules.Zobrist

/** Fixed-depth negamax α-β with TT support. Used via the public
  * factory [[Search.alphaBeta]].
  *
  * Score convention everywhere in this file is from the **side-to-move**
  * perspective (negamax). The evaluator returns white-POV though, so
  * leaf scores get negated on black-to-move before bubbling up.
  *
  * Search returns `None` only when the root position has zero legal
  * moves. Internally, terminal positions deeper down score as
  * `-MateScore + ply` (the side to move has been checkmated) or `0`
  * (stalemate, a draw).
  */
private[engine] final class AlphaBetaSearch(
    eval: Evaluator,
    tt: TranspositionTable,
    book: OpeningBook,
) extends Search:

  import Search.{Infinity, MateScore}
  import TranspositionTable.{Entry, Kind}

  // Killer-move table — at each `ply`, two slots for quiet moves that
  // caused a β-cutoff at this depth. Ordered by recency: slot 0 is the
  // most recent killer, slot 1 the previous one. Tried right after
  // capture-ordered moves; very cheap to maintain and historically
  // worth ~20% search reduction on top of MVV-LVA. `MaxPly` is a
  // hard cap — practical searches stay well under 32 plies.
  //
  // Single-Search shared state: AlphaBetaSearch instances aren't
  // expected to handle concurrent calls (engine is sequential per
  // game), and the table is overwritten on each search anyway, so a
  // mutable Array is safe.
  private inline val MaxPly = 64
  private val killer0: Array[Move] = new Array[Move](MaxPly)
  private val killer1: Array[Move] = new Array[Move](MaxPly)

  // History heuristic — `historyTable(fromSq)(toSq)` accumulates
  // depth² each time the (from→to) move causes a β-cutoff (for
  // quiet moves only). Refines [[scoreMove]]'s quiet-move ranking
  // beyond what killers can cover (2 moves per ply isn't enough
  // when the tree visits the same quiet move at many different
  // plies). A historically-strong move is tried earlier, which
  // compounds cutoffs.
  //
  // Same race tolerance as the killer table: torn reads/writes
  // from concurrent searches yield suboptimal ordering, never an
  // incorrect move pick.
  private val historyTable: Array[Array[Int]] = Array.ofDim[Int](64, 64)

  // LMR move-index threshold: the first N moves at any node are
  // searched at full depth; only moves past index N qualify for
  // reduction. 3 is a conservative default — first 3 are
  // typically the TT bestMove + the top two captures (or the
  // killer), which we don't want to reduce.
  private inline val LmrMoveThreshold = 3

  // LMR minimum depth — below this, the search is too shallow to
  // benefit from reducing further (reduction would land at depth 0
  // which is just an eval).
  private inline val LmrMinDepth = 3

  /** Centipawn value used for MVV-LVA scoring. Matches the
    * classic Kaufman values; king set to a value far above any
    * other so capturing the king (which shouldn't happen — illegal —
    * but in case) ranks above everything. */
  private inline def pieceValue(pt: PieceType): Int = pt match
    case PieceType.Pawn   => 100
    case PieceType.Knight => 320
    case PieceType.Bishop => 330
    case PieceType.Rook   => 500
    case PieceType.Queen  => 900
    case PieceType.King   => 20_000

  def bestMove(
      state: GameState,
      depth: Int,
      history: Set[Long],
  ): UIO[Option[Move]] =
    // Book lookup short-circuits search when the position is known —
    // returning Some(bookMove) skips the α-β work entirely. On miss
    // (or book exhausted) we fall through to native search.
    book.lookup(state).flatMap {
      case Some(move) => ZIO.some(move)
      case None       =>
        clearKillers()
        clearHistory()
        ZIO.succeed(syncBestMove(state, depth, history))
    }

  /** Reset both killer slots for every ply at the start of a new
    * search. Stale killers from a prior search would still trigger
    * α-β cutoffs correctly (move legality is checked at use), but
    * they'd be poorly tuned to the current position. */
  private def clearKillers(): Unit =
    java.util.Arrays.fill(killer0.asInstanceOf[Array[AnyRef]], null)
    java.util.Arrays.fill(killer1.asInstanceOf[Array[AnyRef]], null)

  /** Reset the history table at the start of each fresh search.
    * Like killers, stale history is still safe (just suboptimal
    * ordering); the explicit reset keeps ordering decisions tied
    * to the current root position rather than carrying over biases
    * from a prior game phase. */
  private def clearHistory(): Unit =
    var i = 0
    while i < 64 do
      java.util.Arrays.fill(historyTable(i), 0)
      i += 1

  /** Pick the move at the root that maximises the negamax score for
    * the side to move. Mirrors the negamax recursion below but tracks
    * the *move* (not just the score) so we can return it. */
  private def syncBestMove(
      state: GameState,
      depth: Int,
      history: Set[Long],
  ): Option[Move] =
    val moves = RulesAdapter.legalMoves(state)
    if moves.isEmpty then None
    else
      val rootHash = Zobrist.hash(state)
      val ordered  = orderMoves(moves, state, rootHash, ply = 0)
      var alpha = -Infinity
      val beta  = Infinity
      // Seed `best` with the first move so we always return a move when
      // there's at least one legal option — even if every option scores
      // identically (pathological "all lose by mate" case, or all-tied
      // material positions where the eval flatlines).
      var best: Option[Move] = Some(ordered.head)
      var bestScore = -Infinity
      // Add the root position to history so the recursive search can
      // detect repetition without a separate "path" parameter — every
      // descendant inherits the full ancestor set.
      val rootHistory = history + rootHash
      val it = ordered.iterator
      while it.hasNext do
        val move = it.next()
        RulesAdapter.applyMove(state, move).foreach { next =>
          val score = -negamax(next, depth - 1, -beta, -alpha, ply = 1, rootHistory)
          if score > bestScore then
            bestScore = score
            best = Some(move)
          if score > alpha then alpha = score
        }
      best

  /** Negamax core.
    *
    * Returns the side-to-move score of `state` under an α-β window. On
    * TT hit at sufficient depth the stored score is reused directly
    * (subject to bound tightness vs the current window). On miss the
    * search recurses, then writes the result back keyed by Zobrist.
    *
    * Termination cases:
    *   - 50-move rule hit (halfmoveClock ≥ 100) → draw, score 0
    *   - Position seen before in `history`      → draw, score 0
    *   - depth ≤ 0           → static eval (leaf)
    *   - no legal moves +
    *     side-to-move in check → mate, score `-(MateScore - ply)`
    *   - no legal moves +
    *     side-to-move safe    → stalemate, score 0
    *
    * The repetition + 50-move checks happen BEFORE the TT probe — they
    * can't be cached by Zobrist alone (50-move depends on the clock,
    * repetition depends on the path), so a TT hit from a different
    * search line would corrupt the result.
    */
  private def negamax(
      state: GameState,
      depth: Int,
      alpha: Int,
      beta: Int,
      ply: Int,
      history: Set[Long],
  ): Int =
    if state.halfmoveClock >= 100 then 0
    else
      val hash = Zobrist.hash(state)
      if history.contains(hash) then 0
      else
        probeTt(hash, depth, alpha, beta) match
          case Some(score) => score
          case None =>
            if depth <= 0 then leafEval(state)
            else
              val moves = RulesAdapter.legalMoves(state)
              if moves.isEmpty then terminalScore(state, ply)
              else
                searchMoves(state, moves, depth, alpha, beta, ply, hash, history + hash)

  /** Static evaluation at a leaf node, normalised to side-to-move POV.
    * The Evaluator hands back white-POV centipawns; flip on black. */
  private def leafEval(state: GameState): Int =
    val raw = eval.evaluate(state)
    if state.activeColor == Color.White then raw else -raw

  /** Score for "the side to move has no legal moves". In-check → mate
    * (scaled by `ply` so shorter mates beat longer ones); not in check
    * → stalemate (draw). */
  private def terminalScore(state: GameState, ply: Int): Int =
    if RulesAdapter.isInCheck(state) then -(MateScore - ply)
    else 0

  /** Iterate candidates with α-β cutoff, write the result to the TT,
    * return the best score. `historyWithThis` already contains the
    * current node's Zobrist so children can detect repetitions
    * against it.
    *
    * Two refinements layered on top of plain α-β:
    *   - Late move reductions (LMR): for quiet, non-killer moves
    *     past index [[LmrMoveThreshold]] at depth ≥ [[LmrMinDepth]],
    *     search at depth-2 (one ply less than usual). If the reduced
    *     search returns a score > α, the reduction was wrong, so
    *     re-search at full depth. The intuition is that move
    *     ordering puts the most promising moves first; later
    *     moves rarely improve α, so paying full depth for them is
    *     wasted work. The re-search guards correctness.
    *   - History bookkeeping on cutoffs: when a quiet move causes a
    *     β-cutoff, bump `historyTable(from)(to)` by depth² so future
    *     ordering at OTHER nodes tries the move earlier. Killer
    *     update + history bookkeeping happen together for quiet
    *     cutoffs. */
  private def searchMoves(
      state: GameState,
      moves: List[Move],
      depth: Int,
      alpha: Int,
      beta: Int,
      ply: Int,
      hash: Long,
      historyWithThis: Set[Long],
  ): Int =
    val ordered = orderMoves(moves, state, hash, ply)
    val inCheckHere = RulesAdapter.isInCheck(state)
    var alphaCur  = alpha
    var bestScore = -Infinity
    var bestMove: Option[Move] = None
    var cutoff = false
    var moveIndex = 0
    val k0Here = if ply < MaxPly then killer0(ply) else null
    val k1Here = if ply < MaxPly then killer1(ply) else null
    val it = ordered.iterator
    while it.hasNext && !cutoff do
      val move = it.next()
      RulesAdapter.applyMove(state, move).foreach { next =>
        val capture = isCapture(state, move)
        val isKiller =
          (k0Here != null && k0Here == move) ||
            (k1Here != null && k1Here == move)
        // LMR conditions: depth deep enough to reduce, move late
        // enough in the order, quiet (not a capture, not a killer),
        // not in check at this node (escape moves shouldn't be
        // reduced). Promotions also skip the reduction implicitly
        // because the rules layer doesn't include them in the
        // capture set for ordering but they're "loud" enough that
        // LMR's "quiet move probably won't improve α" assumption is
        // weak — caller note rather than enforced.
        val reduce =
          depth >= LmrMinDepth &&
            moveIndex >= LmrMoveThreshold &&
            !capture &&
            !isKiller &&
            !inCheckHere
        val searchDepth = if reduce then depth - 2 else depth - 1
        var score = -negamax(next, searchDepth, -beta, -alphaCur, ply + 1, historyWithThis)
        // If the reduced search hinted at an improvement, re-search
        // at full depth to confirm. This preserves correctness — a
        // missed improvement at one node would be caught here.
        if reduce && score > alphaCur then
          score = -negamax(next, depth - 1, -beta, -alphaCur, ply + 1, historyWithThis)
        if score > bestScore then
          bestScore = score
          bestMove = Some(move)
        if score > alphaCur then alphaCur = score
        if alphaCur >= beta then
          cutoff = true
          if !capture then
            recordKiller(ply, move)
            // History bonus = depth²: a cutoff caused at high depth
            // is much more informative than one at low depth.
            historyTable(move.from.squareIdx)(move.to.squareIdx) += depth * depth
      }
      moveIndex += 1
    val kind =
      if bestScore <= alpha then Kind.Upper
      else if bestScore >= beta then Kind.Lower
      else Kind.Exact
    tt.put(hash, Entry(depth, bestScore, kind, bestMove))
    bestScore

  /** Move ordering. Order, from highest priority to lowest:
    *   1. The TT bestMove (if present and legal in this list) —
    *      historically the most-likely-to-cause-a-cutoff candidate.
    *   2. Captures, ranked by MVV-LVA (most valuable victim, least
    *      valuable attacker). queen×pawn beats pawn×queen.
    *   3. Killer moves at this ply — quiet moves that previously
    *      caused a β-cutoff. Two slots, newest first.
    *   4. Quiet moves in generation order.
    *
    * Sorting allocates an intermediate `List[(Int, Move)]` per node;
    * we eat that cost because the cutoffs it enables eliminate
    * exponentially more work downstream. */
  private def orderMoves(
      moves: List[Move],
      state: GameState,
      hash: Long,
      ply: Int,
  ): List[Move] =
    val ttBest = tt.get(hash).flatMap(_.bestMove)
    val k0 = if ply < MaxPly then killer0(ply) else null
    val k1 = if ply < MaxPly then killer1(ply) else null
    moves.sortBy(m => -scoreMove(state, m, ttBest, k0, k1))

  /** Per-move ordering score. Higher is tried first. Score buckets:
    *   - 1_000_000           TT bestMove
    *   -   100_000           any capture (MVV-LVA tiebreak: victim×10 − attacker)
    *   -    90_000           killer slot 0 (most recent)
    *   -    80_000           killer slot 1
    *   -        0..79_999    quiet — history-heuristic score (capped at
    *                         79_999 so a hot history entry can't sneak
    *                         past a killer)
    *
    * Capture detection uses `BoardState.get(move.to)` — cheap (a
    * dozen mask checks) and handles all normal captures. En passant
    * has no piece on `move.to` so it ranks as quiet; that's a false
    * negative but EP is rare enough not to matter for ordering. */
  private def scoreMove(
      state: GameState,
      move: Move,
      ttBest: Option[Move],
      k0: Move,
      k1: Move,
  ): Int =
    if ttBest.contains(move) then 1_000_000
    else
      val capturedPiece = state.board.get(move.to)
      capturedPiece match
        case Some(captured) =>
          val victimVal = pieceValue(captured.pieceType)
          val attackerVal = state.board.get(move.from)
            .map(p => pieceValue(p.pieceType))
            .getOrElse(0)
          100_000 + victimVal * 10 - attackerVal
        case None =>
          if k0 != null && k0 == move then 90_000
          else if k1 != null && k1 == move then 80_000
          else
            // History heuristic: quiet moves with a higher cutoff
            // count get tried earlier. Cap below the killer bucket so
            // a hot history entry never pre-empts a killer.
            math.min(historyTable(move.from.squareIdx)(move.to.squareIdx), 79_999)

  /** True if `move` captures a piece on its destination. False for
    * en passant (rare; ok to treat as quiet for ordering). */
  private def isCapture(state: GameState, move: Move): Boolean =
    state.board.contains(move.to)

  /** Record a quiet move that caused a β-cutoff as a killer for
    * this ply. Slides slot 0 → slot 1 to retain the two most recent
    * killers. A duplicate of slot 0 is a no-op — we don't want both
    * slots holding the same move. */
  private def recordKiller(ply: Int, move: Move): Unit =
    if ply < MaxPly && killer0(ply) != move then
      killer1(ply) = killer0(ply)
      killer0(ply) = move

  /** Read the TT entry for `hash` (if any) and decide whether it
    * settles the current α/β query.
    *
    * - Exact and depth ≥ requested → return stored score.
    * - Lower bound ≥ β             → cutoff: stored is already too good.
    * - Upper bound ≤ α             → cutoff: stored caps the value below α.
    *
    * Other cases (e.g. an Upper bound between α and β) leave the
    * caller to re-search; the TT entry's bestMove will still be used
    * for ordering.
    */
  private def probeTt(hash: Long, depth: Int, alpha: Int, beta: Int): Option[Int] =
    tt.get(hash) match
      case Some(entry) if entry.depth >= depth =>
        entry.kind match
          case Kind.Exact                            => Some(entry.score)
          case Kind.Lower if entry.score >= beta     => Some(entry.score)
          case Kind.Upper if entry.score <= alpha    => Some(entry.score)
          case _                                     => None
      case _ => None
