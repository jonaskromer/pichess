package chess.bot.engine

import zio.{UIO, ZIO}

import chess.bot.engine.internal.RulesAdapter
import chess.model.board.{GameState, Move, MoveInt, Position}
import chess.model.piece.{Color, PieceType}
import chess.model.rules.Zobrist

/** Fixed-depth negamax α-β with TT support. Used via the public
  * factory [[Search.alphaBeta]].
  *
  * Score convention everywhere in this file is from the **side-to-move**
  * perspective (negamax). The evaluator returns white-POV though, so
  * leaf scores get negated on black-to-move before bubbling up.
  *
  * Internally, moves are passed around as 32-bit `Int`s ([[MoveInt]]
  * encoding) — no `Move` case-class allocations in the search hot
  * loop. The public surface still returns a `Move` (decoded once at
  * the end) so callers and codecs see no change.
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
  // most recent killer, slot 1 the previous one. Stored as packed Int
  // ([[MoveInt]] encoding); -1 is the sentinel for "no killer" (a real
  // move can't encode as -1 because the high bits are 0).
  //
  // Single-Search shared state: race-tolerant — corrupt killers just
  // mean suboptimal ordering, never an incorrect move pick.
  private inline val MaxPly = 64
  private inline val NoKiller = -1
  private val killer0: Array[Int] = Array.fill(MaxPly)(NoKiller)
  private val killer1: Array[Int] = Array.fill(MaxPly)(NoKiller)

  // History heuristic — `historyTable(fromSq)(toSq)` accumulates
  // depth² each time the (from→to) move causes a β-cutoff (quiet
  // moves only). Same race tolerance as the killer table.
  private val historyTable: Array[Array[Int]] = Array.ofDim[Int](64, 64)

  // LMR thresholds. See doc-comments on `searchMoves` for tuning.
  private inline val LmrMoveThreshold = 3
  private inline val LmrMinDepth      = 3

  // Maximum number of legal moves from any chess position. 218 is
  // the theoretical max (a contrived position with many promotions);
  // 256 leaves comfortable headroom and aligns to a cache-friendly
  // power-of-two.
  private inline val MaxMovesPerNode = 256

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

  /** Search-call-scoped scratch buffers — one move list + one
    * scored-pack list per ply. Allocated fresh on each
    * [[bestMove]] call so concurrent calls (e.g. parallel test
    * runs) don't share mutable state. Per-call cost: ~64 KB for
    * the whole stack, trivial vs the ~100 MB of allocations
    * elsewhere in a depth-4 search. */
  private final class SearchBufs:
    val moves:  Array[Array[Int]]  = Array.fill(MaxPly + 1)(new Array[Int](MaxMovesPerNode))
    val scored: Array[Array[Long]] = Array.fill(MaxPly + 1)(new Array[Long](MaxMovesPerNode))

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
        val bufs = new SearchBufs
        ZIO.succeed(syncBestMove(state, depth, history, bufs).map(MoveInt.decode))
    }

  /** Reset both killer slots for every ply at the start of a new
    * search. Stale killers from a prior search would still trigger
    * α-β cutoffs correctly (move legality is checked at use), but
    * they'd be poorly tuned to the current position. */
  private def clearKillers(): Unit =
    java.util.Arrays.fill(killer0, NoKiller)
    java.util.Arrays.fill(killer1, NoKiller)

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
    * the side to move. Returns the chosen move's [[MoveInt]]
    * encoding; the public [[bestMove]] decodes once at the boundary. */
  private def syncBestMove(
      state: GameState,
      depth: Int,
      history: Set[Long],
      bufs: SearchBufs,
  ): Option[Int] =
    val moveBuf = bufs.moves(0)
    val count = RulesAdapter.fillLegalMoves(state, moveBuf)
    if count == 0 then None
    else
      val rootHash = Zobrist.hash(state)
      val ordered = bufs.scored(0)
      orderMovesInto(moveBuf, count, ordered, state, rootHash, ply = 0)
      var alpha = -Infinity
      val beta  = Infinity
      // Seed `best` with the first ordered move so we always return
      // something for a legal position.
      var best: Int = MoveInt.fromPacked(ordered(count - 1))
      var bestScore = -Infinity
      val rootHistory = history + rootHash
      // Iterate from the END of the ascending-sorted scored array
      // → highest score first (descending order of preference).
      var i = count - 1
      while i >= 0 do
        val move = MoveInt.fromPacked(ordered(i))
        RulesAdapter.applyMoveInt(state, move).foreach { next =>
          val score = -negamax(next, depth - 1, -beta, -alpha, ply = 1, rootHistory, bufs)
          if score > bestScore then
            bestScore = score
            best = move
          if score > alpha then alpha = score
        }
        i -= 1
      Some(best)

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
      bufs: SearchBufs,
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
            else if ply >= MaxPly then leafEval(state)
            else
              val moveBuf = bufs.moves(ply)
              val count = RulesAdapter.fillLegalMoves(state, moveBuf)
              if count == 0 then terminalScore(state, ply)
              else
                searchMoves(
                  state, moveBuf, count, depth, alpha, beta, ply, hash,
                  history + hash, bufs,
                )

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
      moveBuf: Array[Int],
      count: Int,
      depth: Int,
      alpha: Int,
      beta: Int,
      ply: Int,
      hash: Long,
      historyWithThis: Set[Long],
      bufs: SearchBufs,
  ): Int =
    val ordered = bufs.scored(ply)
    orderMovesInto(moveBuf, count, ordered, state, hash, ply)
    val inCheckHere = RulesAdapter.isInCheck(state)
    var alphaCur  = alpha
    var bestScore = -Infinity
    var bestMove: Int = NoKiller   // NoKiller (-1) doubles as "no best move yet"
    var cutoff = false
    var moveIndex = 0
    val k0Here = if ply < MaxPly then killer0(ply) else NoKiller
    val k1Here = if ply < MaxPly then killer1(ply) else NoKiller
    // Iterate from the END of the ascending-sorted scored array
    // → highest score first.
    var i = count - 1
    while i >= 0 && !cutoff do
      val move = MoveInt.fromPacked(ordered(i))
      RulesAdapter.applyMoveInt(state, move).foreach { next =>
        val capture = isCapture(state, move)
        val isKiller = move == k0Here || move == k1Here
        val reduce =
          depth >= LmrMinDepth &&
            moveIndex >= LmrMoveThreshold &&
            !capture &&
            !isKiller &&
            !inCheckHere
        val searchDepth = if reduce then depth - 2 else depth - 1
        var score = -negamax(next, searchDepth, -beta, -alphaCur, ply + 1, historyWithThis, bufs)
        // Re-search at full depth if the reduction's "first guess"
        // looks promising. Preserves correctness while paying full
        // depth only when needed.
        if reduce && score > alphaCur then
          score = -negamax(next, depth - 1, -beta, -alphaCur, ply + 1, historyWithThis, bufs)
        if score > bestScore then
          bestScore = score
          bestMove = move
        if score > alphaCur then alphaCur = score
        if alphaCur >= beta then
          cutoff = true
          if !capture then
            recordKiller(ply, move)
            historyTable(MoveInt.fromIdx(move))(MoveInt.toIdx(move)) += depth * depth
      }
      moveIndex += 1
      i -= 1
    val kind =
      if bestScore <= alpha then Kind.Upper
      else if bestScore >= beta then Kind.Lower
      else Kind.Exact
    // TT entry still carries an `Option[Move]` (the old codec/wire
    // shape). Decode here at the boundary — only when we actually
    // write a TT entry, which is far less frequent than the inner
    // loop's per-move work.
    val bestMoveOpt = if bestMove == NoKiller then None else Some(MoveInt.decode(bestMove))
    tt.put(hash, Entry(depth, bestScore, kind, bestMoveOpt))
    bestScore

  /** Move ordering. Writes (score, move) pairs into `scoredOut` as
    * packed Longs and sorts ascending (so the highest-score move is
    * at the end of the array — callers iterate in reverse). Order,
    * from highest priority to lowest:
    *   1. The TT bestMove (if present and legal in this list) —
    *      historically the most-likely-to-cause-a-cutoff candidate.
    *   2. Captures, ranked by MVV-LVA (most valuable victim, least
    *      valuable attacker). queen×pawn beats pawn×queen.
    *   3. Killer moves at this ply — quiet moves that previously
    *      caused a β-cutoff. Two slots, newest first.
    *   4. Quiet moves — ranked by history-heuristic score.
    *
    * Both `moveBuf` and `scoredOut` must have at least `count`
    * elements available. */
  private def orderMovesInto(
      moveBuf: Array[Int],
      count: Int,
      scoredOut: Array[Long],
      state: GameState,
      hash: Long,
      ply: Int,
  ): Unit =
    val ttBest = tt.get(hash).flatMap(_.bestMove).fold(NoKiller)(MoveInt.encodeMove)
    val k0 = if ply < MaxPly then killer0(ply) else NoKiller
    val k1 = if ply < MaxPly then killer1(ply) else NoKiller
    var i = 0
    while i < count do
      val m = moveBuf(i)
      val score = scoreMove(state, m, ttBest, k0, k1)
      scoredOut(i) = MoveInt.pack(score, insertionIdx = i, move = m)
      i += 1
    java.util.Arrays.sort(scoredOut, 0, count)

  /** Per-move ordering score. Higher is tried first. Score buckets:
    *   - 1_000_000           TT bestMove
    *   -   100_000           any capture (MVV-LVA tiebreak: victim×10 − attacker)
    *   -    90_000           killer slot 0 (most recent)
    *   -    80_000           killer slot 1
    *   -        0..79_999    quiet — history-heuristic score (capped at
    *                         79_999 so a hot history entry can't sneak
    *                         past a killer) */
  private def scoreMove(
      state: GameState,
      move: Int,
      ttBest: Int,
      k0: Int,
      k1: Int,
  ): Int =
    if move == ttBest then 1_000_000
    else
      val capturedPiece = state.board.get(positionAt(MoveInt.toIdx(move)))
      capturedPiece match
        case Some(captured) =>
          val victimVal = pieceValue(captured.pieceType)
          val attackerVal = state.board.get(positionAt(MoveInt.fromIdx(move)))
            .map(p => pieceValue(p.pieceType))
            .getOrElse(0)
          100_000 + victimVal * 10 - attackerVal
        case None =>
          if move == k0 then 90_000
          else if move == k1 then 80_000
          else
            math.min(historyTable(MoveInt.fromIdx(move))(MoveInt.toIdx(move)), 79_999)

  /** Decode a LERF square index back to the cached [[Position]]
    * flyweight — no allocation. */
  private inline def positionAt(idx: Int): Position =
    Position(('a' + (idx % 8)).toChar, idx / 8 + 1)

  /** True if `move` captures a piece on its destination. False for
    * en passant (rare; ok to treat as quiet for ordering). */
  private def isCapture(state: GameState, move: Int): Boolean =
    state.board.contains(positionAt(MoveInt.toIdx(move)))

  /** Record a quiet move that caused a β-cutoff as a killer for
    * this ply. */
  private def recordKiller(ply: Int, move: Int): Unit =
    if ply < MaxPly && killer0(ply) != move then
      killer1(ply) = killer0(ply)
      killer0(ply) = move

  /** Read the TT entry for `hash` (if any) and decide whether it
    * settles the current α/β query. */
  private def probeTt(hash: Long, depth: Int, alpha: Int, beta: Int): Option[Int] =
    tt.get(hash) match
      case Some(entry) if entry.depth >= depth =>
        entry.kind match
          case Kind.Exact                            => Some(entry.score)
          case Kind.Lower if entry.score >= beta     => Some(entry.score)
          case Kind.Upper if entry.score <= alpha    => Some(entry.score)
          case _                                     => None
      case _ => None
