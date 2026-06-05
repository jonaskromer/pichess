package chess.bot.engine

import zio.{UIO, ZIO}

import chess.bot.engine.internal.{RulesAdapter, StaticExchange}
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
    parallelism: Int = 1,
    // See `Search.alphaBeta` for the empirical Elo finding behind the
    // OFF default.
    counterMoveEnabled: Boolean = false,
    // Toggle for the leaf-level quiescence search (replaces the bare
    // static eval at depth ≤ 0 with a stand-pat + capture-only
    // recursion that escapes the horizon effect). See
    // [[Search.alphaBeta]] for the empirical Elo finding.
    quiescenceEnabled: Boolean = true,
    // Toggle for SEE-based capture ordering — losing captures
    // (SEE < 0) get demoted below quiet moves. Defaults OFF until
    // the A/B head-to-head confirms the textbook +20-40 Elo.
    seeEnabled: Boolean = false,
    // Iterative deepening: when ON, [[bestMove(state, depth)]]
    // runs depths 1..depth in order, sharing the TT so each
    // iteration seeds the next one's move ordering. Same final
    // depth, better cutoffs from the warmed TT.
    iterativeDeepeningEnabled: Boolean = false,
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

  // Counter-move heuristic — for the move the opponent just
  // played (`prevFrom` → `prevTo`), remember the quiet move that
  // most recently caused a β-cutoff in reply. Sub-killer priority
  // in [[scoreMove]]; cheap to maintain (one 64×64 array, single
  // write on cutoff), and well-known to add ~20-40 Elo on top of
  // killers + history because it captures the "if X is played at
  // me, Y is my refutation" pattern that killers (per-ply only)
  // can't.
  //
  // `NoKiller` (-1) is the "no counter known" sentinel — same
  // value chosen as the killer table's "no killer" so the
  // scoring helpers can compare with `==` against `Int`.
  // Race-tolerant just like the other ordering heuristics.
  private val counterMoveTable: Array[Array[Int]] = Array.fill(64, 64)(NoKiller)

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

  /** Search-call-scoped scratch buffers — one capture list, one
    * quiet-move list, and one scored-pack list per ply. Allocated
    * fresh on each [[bestMove]] call so concurrent calls (e.g.
    * parallel test runs) don't share mutable state. Per-call
    * cost: ~128 KB for the whole stack, trivial vs the ~100 MB
    * of allocations elsewhere in a depth-4 search.
    *
    * The split into separate `captures` and `quiets` buffers
    * supports the two-stage move generator: stage 1 fills both
    * via one rules-layer call, stage 1 iterates captures with
    * MVV-LVA ordering, and if no α-β cutoff fires, stage 2
    * iterates quiets with history ordering. `scored` is reused
    * between the two stages because we never need them
    * simultaneously. */
  private final class SearchBufs:
    val captures: Array[Array[Int]]  = Array.fill(MaxPly + 1)(new Array[Int](MaxMovesPerNode))
    val quiets:   Array[Array[Int]]  = Array.fill(MaxPly + 1)(new Array[Int](MaxMovesPerNode))
    val scored:   Array[Array[Long]] = Array.fill(MaxPly + 1)(new Array[Long](MaxMovesPerNode))

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
        clearCounterMoves()
        if iterativeDeepeningEnabled then iterativeBestMove(state, depth, history)
        else if parallelism > 1 then parallelBestMove(state, depth, history)
        else
          val bufs = new SearchBufs
          ZIO.succeed(syncBestMove(state, depth, history, bufs).map(MoveInt.decode))
    }

  /** Iterative-deepening wrapper: runs the search at depth 1, 2, …,
    * target, sharing the TT across iterations. Each iteration's
    * `bestMove` for every visited node is left in the TT, so the
    * next iteration's move ordering starts from a strong prior —
    * the TT bestMove (1_000_000 ordering bucket) is exactly the
    * move that was best one ply shallower. That single change
    * usually flips the search from "lots of late cutoffs" to
    * "cutoff on the first move tried", which dwarfs the extra
    * shallow-depth work.
    *
    * Forced-mate short-circuit: if a shallower depth finds a
    * mate-in-N score, deeper iterations can't improve it, so we
    * return early. Avoids wasting time at high depth when the
    * tactical answer is already nailed down.
    *
    * Parallelism: each iteration honours the configured
    * `parallelism` setting, so YBWC and ID compose naturally —
    * shallow iterations are serial (tree is small), deep ones
    * fan out at the root. */
  private def iterativeBestMove(
      state: GameState,
      depth: Int,
      history: Set[Long],
  ): UIO[Option[Move]] =
    def loop(d: Int, last: Option[Move]): UIO[Option[Move]] =
      if d > depth then ZIO.succeed(last)
      else
        val iterEffect =
          if parallelism > 1 then parallelBestMove(state, d, history)
          else
            val bufs = new SearchBufs
            ZIO.succeed(syncBestMove(state, d, history, bufs).map(MoveInt.decode))
        iterEffect.flatMap { result =>
          // Mate-in-N is already found; no point going deeper.
          val rootHash = Zobrist.hash(state)
          val rootScore = tt.get(rootHash).map(_.score).getOrElse(0)
          val mateFound = math.abs(rootScore) >= MateScore - MaxPly
          if mateFound then ZIO.succeed(result)
          else loop(d + 1, result)
        }
    loop(1, None)

  /** YBWC-style ("Young Brothers Wait Concept") parallel root
    * search.
    *
    * Step 1: serially search the first-ordered move (the "elder
    * brother") to establish a real α. With good move ordering
    * (TT bestMove + MVV-LVA), this gives a tight α before any
    * parallelism kicks in.
    *
    * Step 2: fan out the remaining moves across fibers, each
    * recursing with `(-β, -α)` as their α-β window. Fibers that
    * can't improve on the established α cut off quickly inside
    * their subtree, so the "wasted work" cost of simple parallel
    * root is bounded by how many remaining moves actually beat
    * the elder brother's score.
    *
    * Tradeoff vs serial: the first move is searched serially so
    * we keep its α-β benefit; for the rest, parallel fibers can't
    * see each other's α updates, so further pruning after the
    * elder brother's score is one-way. In tactical positions
    * where the elder brother's score is decisive, this approaches
    * ideal parallel speedup. In quiet positions where many moves
    * tie at the eval, parallel still wastes work.
    *
    * TT shared (`ConcurrentHashMap`, thread-safe). Killer /
    * history tables shared but race-tolerant. Per-fiber
    * [[SearchBufs]] keep the move buffers isolated. */
  private def parallelBestMove(
      state: GameState,
      depth: Int,
      history: Set[Long],
  ): UIO[Option[Move]] =
    val rootBufs = new SearchBufs
    val capBuf   = rootBufs.captures(0)
    val quietBuf = rootBufs.quiets(0)
    val (capCount, quietCount) = RulesAdapter.fillCapturesAndQuiets(state, capBuf, quietBuf)
    if capCount == 0 && quietCount == 0 then ZIO.none
    else
      val rootHash    = Zobrist.hash(state)
      val rootHistory = history + rootHash

      // Order both stages so the elder brother is the
      // best-by-ordering move (TT > captures > killers > counter > history).
      // No `prevMove` at the root, so counter ordering is disabled here.
      val scoredCap = bufsScoredFor(rootBufs, 0)
      orderMovesInto(capBuf, capCount, scoredCap, state, rootHash, ply = 0, prevMove = NoKiller)
      val scoredQuiet = bufsScoredFor(rootBufs, 1)
      orderMovesInto(quietBuf, quietCount, scoredQuiet, state, rootHash, ply = 0, prevMove = NoKiller)

      // Materialise into a flat array in iteration order (highest
      // score first — captures bucket, then quiets bucket).
      val total = capCount + quietCount
      val moves = new Array[Int](total)
      var n = 0
      var i = capCount - 1
      while i >= 0 do
        moves(n) = MoveInt.fromPacked(scoredCap(i))
        n += 1
        i -= 1
      var k = quietCount - 1
      while k >= 0 do
        moves(n) = MoveInt.fromPacked(scoredQuiet(k))
        n += 1
        k -= 1

      // Step 1: elder brother serial search.
      val elderMove = moves(0)
      val elderScore: Int =
        RulesAdapter.applyMoveInt(state, elderMove) match
          case Some(next) =>
            -negamax(next, depth - 1, -Infinity, Infinity, ply = 1, rootHistory, rootBufs, prevMove = elderMove)
          case None       => -Infinity

      if total == 1 then ZIO.some(MoveInt.decode(elderMove))
      else
        // Step 2: parallel young brothers, each starting with
        // alpha = elderScore. A fiber that can't improve cuts
        // off inside its subtree.
        ZIO
          .foreachPar(1 until total) { idx =>
            ZIO.succeed {
              val move = moves(idx)
              val bufs = new SearchBufs
              val score: Int =
                RulesAdapter.applyMoveInt(state, move) match
                  case Some(next) =>
                    -negamax(next, depth - 1, -Infinity, -elderScore, ply = 1, rootHistory, bufs, prevMove = move)
                  case None       => -Infinity
              (move, score)
            }
          }
          .withParallelism(parallelism)
          .map { youngerResults =>
            // Combine elder + youngers; pick the highest.
            var best       = elderMove
            var bestScore  = elderScore
            youngerResults.foreach { case (m, s) =>
              if s > bestScore then
                bestScore = s
                best = m
            }
            Some(MoveInt.decode(best))
          }

  /** Tiny accessor so the YBWC root code can reuse the same
    * pre-allocated scored buffer for both the captures and quiets
    * passes without rebuilding it. Index 0/1 instead of `ply` here
    * because at the root we only have one node but two stages. */
  private inline def bufsScoredFor(bufs: SearchBufs, slot: Int): Array[Long] =
    bufs.scored(slot)

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

  /** Reset the counter-move table for the same reason as the
    * killer table — stale entries from a prior search position
    * would suggest the wrong refutation. */
  private def clearCounterMoves(): Unit =
    var i = 0
    while i < 64 do
      java.util.Arrays.fill(counterMoveTable(i), NoKiller)
      i += 1

  /** Pick the move at the root that maximises the negamax score for
    * the side to move. Returns the chosen move's [[MoveInt]]
    * encoding; the public [[bestMove]] decodes once at the boundary.
    *
    * Two-stage move generation — captures first, then quiets. The
    * root has no β-cutoff (β = +∞) so we always iterate both
    * stages, but the split still pays for itself: sorting 5 + 25
    * moves is cheaper than sorting 30, and the sort cache stays
    * smaller. */
  private def syncBestMove(
      state: GameState,
      depth: Int,
      history: Set[Long],
      bufs: SearchBufs,
  ): Option[Int] =
    val capBuf   = bufs.captures(0)
    val quietBuf = bufs.quiets(0)
    val (capCount, quietCount) = RulesAdapter.fillCapturesAndQuiets(state, capBuf, quietBuf)
    if capCount == 0 && quietCount == 0 then None
    else
      val rootHash = Zobrist.hash(state)
      val rootHistory = history + rootHash
      var alpha = -Infinity
      val beta  = Infinity
      var bestScore = -Infinity
      // Seed `best` with the first available move so we always
      // return something for a legal position (captures preferred).
      var best: Int =
        if capCount > 0 then capBuf(0)
        else quietBuf(0)

      // Stage 1: captures
      if capCount > 0 then
        val scored = bufs.scored(0)
        orderMovesInto(capBuf, capCount, scored, state, rootHash, ply = 0, prevMove = NoKiller)
        var i = capCount - 1
        while i >= 0 do
          val move = MoveInt.fromPacked(scored(i))
          RulesAdapter.applyMoveInt(state, move).foreach { next =>
            val score = -negamax(next, depth - 1, -beta, -alpha, ply = 1, rootHistory, bufs, prevMove = move)
            if score > bestScore then
              bestScore = score
              best = move
            if score > alpha then alpha = score
          }
          i -= 1

      // Stage 2: quiets
      if quietCount > 0 then
        val scored = bufs.scored(0)
        orderMovesInto(quietBuf, quietCount, scored, state, rootHash, ply = 0, prevMove = NoKiller)
        var i = quietCount - 1
        while i >= 0 do
          val move = MoveInt.fromPacked(scored(i))
          RulesAdapter.applyMoveInt(state, move).foreach { next =>
            val score = -negamax(next, depth - 1, -beta, -alpha, ply = 1, rootHistory, bufs, prevMove = move)
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
      prevMove: Int,
  ): Int =
    if state.halfmoveClock >= 100 then 0
    else
      val hash = Zobrist.hash(state)
      if history.contains(hash) then 0
      else
        probeTt(hash, depth, alpha, beta) match
          case Some(score) => score
          case None =>
            if depth <= 0 then leafScore(state, alpha, beta, ply, bufs)
            else if ply >= MaxPly then leafEval(state)
            else
              val capBuf   = bufs.captures(ply)
              val quietBuf = bufs.quiets(ply)
              val (capCount, quietCount) =
                RulesAdapter.fillCapturesAndQuiets(state, capBuf, quietBuf)
              if capCount == 0 && quietCount == 0 then terminalScore(state, ply)
              else
                searchMoves(
                  state, capBuf, capCount, quietBuf, quietCount,
                  depth, alpha, beta, ply, hash, history + hash, bufs, prevMove,
                )

  /** Static evaluation at a leaf node, normalised to side-to-move POV.
    * The Evaluator hands back white-POV centipawns; flip on black. */
  private def leafEval(state: GameState): Int =
    val raw = eval.evaluate(state)
    if state.activeColor == Color.White then raw else -raw

  /** Leaf entry point: routes to either bare static eval or the
    * quiescence search depending on [[quiescenceEnabled]]. Keeping
    * the routing here means the rest of [[negamax]] doesn't need to
    * branch on the toggle.
    *
    * Quiescence eliminates the horizon effect — a depth-0 node whose
    * static eval looks +5 might be a queen that's about to be
    * captured next ply. The qsearch recurses on captures until the
    * position is "quiet" (no more captures, not in check) before
    * returning a static eval. */
  private def leafScore(
      state: GameState,
      alpha: Int,
      beta: Int,
      ply: Int,
      bufs: SearchBufs,
  ): Int =
    if quiescenceEnabled then qSearch(state, alpha, beta, ply, bufs)
    else leafEval(state)

  /** Quiescence search — fixes the horizon effect by recursing on
    * captures (and check evasions) until a stable position is
    * reached. Bounded naturally by piece-count: each capture removes
    * a piece, so the longest pure-capture chain is ~32 plies. The
    * outer `ply >= MaxPly` guard is a final safety.
    *
    * Stand-pat: when not in check, "I refuse to capture and accept
    * the current static eval" is a legitimate option. If that score
    * already ≥ β, no capture can help (we'd cut off anyway), so we
    * return immediately. When in check, we have no choice but to
    * move; stand-pat is skipped and quiet evasions are included
    * alongside captures.
    *
    * No TT lookup or write: qsearch entries are depth-0 and
    * shouldn't shadow real depth-≥1 entries written by the main
    * negamax loop. */
  private def qSearch(
      state: GameState,
      alpha: Int,
      beta: Int,
      ply: Int,
      bufs: SearchBufs,
  ): Int =
    if ply >= MaxPly then leafEval(state)
    else
      val inCheck  = RulesAdapter.isInCheck(state)
      val capBuf   = bufs.captures(ply)
      val quietBuf = bufs.quiets(ply)
      val (capCount, quietCount) =
        RulesAdapter.fillCapturesAndQuiets(state, capBuf, quietBuf)

      if capCount == 0 && quietCount == 0 then
        // No legal moves: mate if in check, stalemate (draw) otherwise.
        if inCheck then -(MateScore - ply) else 0
      else
        var alphaCur  = alpha
        var bestScore = -Infinity
        var cutoff    = false

        if !inCheck then
          val standPat = leafEval(state)
          bestScore = standPat
          if standPat >= beta then cutoff = true
          else if standPat > alphaCur then alphaCur = standPat

        // Captures first, ordered by MVV-LVA only (no TT/killer/
        // counter ordering at the leaf — extra Map lookups cost more
        // than they save when the move list is short).
        if !cutoff && capCount > 0 then
          val scored = bufs.scored(ply)
          orderCapturesMvvLva(capBuf, capCount, scored, state)
          var i = capCount - 1
          while i >= 0 && !cutoff do
            val move = MoveInt.fromPacked(scored(i))
            RulesAdapter.applyMoveInt(state, move).foreach { next =>
              val score = -qSearch(next, -beta, -alphaCur, ply + 1, bufs)
              if score > bestScore then bestScore = score
              if score > alphaCur then alphaCur = score
              if alphaCur >= beta then cutoff = true
            }
            i -= 1

        // Under check we also need quiet escapes — blocking the
        // check, king moves, etc.
        if !cutoff && inCheck && quietCount > 0 then
          var i = 0
          while i < quietCount && !cutoff do
            val move = quietBuf(i)
            RulesAdapter.applyMoveInt(state, move).foreach { next =>
              val score = -qSearch(next, -beta, -alphaCur, ply + 1, bufs)
              if score > bestScore then bestScore = score
              if score > alphaCur then alphaCur = score
              if alphaCur >= beta then cutoff = true
            }
            i += 1

        bestScore

  /** MVV-LVA ordering for the qsearch capture list. Same scoring
    * function as the main [[scoreMove]] capture branch but inlined
    * here so we skip the TT/killer/counter chain. */
  private def orderCapturesMvvLva(
      moveBuf: Array[Int],
      count: Int,
      scoredOut: Array[Long],
      state: GameState,
  ): Unit =
    var i = 0
    while i < count do
      val m = moveBuf(i)
      val victimVal = state.board.get(positionAt(MoveInt.toIdx(m)))
        .map(p => pieceValue(p.pieceType))
        .getOrElse(0)
      val attackerVal = state.board.get(positionAt(MoveInt.fromIdx(m)))
        .map(p => pieceValue(p.pieceType))
        .getOrElse(0)
      val score = victimVal * 10 - attackerVal
      scoredOut(i) = MoveInt.pack(score, insertionIdx = i, move = m)
      i += 1
    java.util.Arrays.sort(scoredOut, 0, count)

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
    * Two-stage lazy move generation:
    *   - Stage 1: captures (MVV-LVA ordered via [[orderMovesInto]]).
    *     Most β-cutoffs in tactical positions land here.
    *   - Stage 2: quiet moves (history-heuristic ordered). Only
    *     reached if Stage 1 didn't cut off — saves the sort +
    *     iterate cost of every quiet move on tactical cutoffs.
    *
    * LMR ([[LmrMoveThreshold]] / [[LmrMinDepth]]) only fires on
    * Stage 2 because Stage 1 moves are all captures (not eligible
    * for reduction). The `moveIndex` counter is shared across both
    * stages so the "first N moves searched at full depth" rule
    * applies as a whole, not per stage.
    *
    * History bonus on cutoff: only stamped for quiet moves (Stage 2)
    * since captures are already ordered by MVV-LVA and don't
    * benefit from cross-node history reinforcement. */
  private def searchMoves(
      state: GameState,
      capBuf: Array[Int],
      capCount: Int,
      quietBuf: Array[Int],
      quietCount: Int,
      depth: Int,
      alpha: Int,
      beta: Int,
      ply: Int,
      hash: Long,
      historyWithThis: Set[Long],
      bufs: SearchBufs,
      prevMove: Int,
  ): Int =
    val inCheckHere = RulesAdapter.isInCheck(state)
    val k0Here = if ply < MaxPly then killer0(ply) else NoKiller
    val k1Here = if ply < MaxPly then killer1(ply) else NoKiller
    val scored = bufs.scored(ply)
    var alphaCur  = alpha
    var bestScore = -Infinity
    var bestMove: Int = NoKiller
    var cutoff = false
    var moveIndex = 0

    // ── Stage 1: captures ───────────────────────────────────────
    if capCount > 0 then
      orderMovesInto(capBuf, capCount, scored, state, hash, ply, prevMove)
      var i = capCount - 1
      while i >= 0 && !cutoff do
        val move = MoveInt.fromPacked(scored(i))
        RulesAdapter.applyMoveInt(state, move).foreach { next =>
          // Captures don't get reduced (always "loud" by definition).
          val score = -negamax(next, depth - 1, -beta, -alphaCur, ply + 1, historyWithThis, bufs, move)
          if score > bestScore then
            bestScore = score
            bestMove = move
          if score > alphaCur then alphaCur = score
          if alphaCur >= beta then cutoff = true
        }
        moveIndex += 1
        i -= 1

    // ── Stage 2: quiet moves (only if Stage 1 didn't cut off) ──
    if !cutoff && quietCount > 0 then
      orderMovesInto(quietBuf, quietCount, scored, state, hash, ply, prevMove)
      var i = quietCount - 1
      while i >= 0 && !cutoff do
        val move = MoveInt.fromPacked(scored(i))
        RulesAdapter.applyMoveInt(state, move).foreach { next =>
          val isKiller = move == k0Here || move == k1Here
          val reduce =
            depth >= LmrMinDepth &&
              moveIndex >= LmrMoveThreshold &&
              !isKiller &&
              !inCheckHere
          val searchDepth = if reduce then depth - 2 else depth - 1
          var score = -negamax(next, searchDepth, -beta, -alphaCur, ply + 1, historyWithThis, bufs, move)
          if reduce && score > alphaCur then
            score = -negamax(next, depth - 1, -beta, -alphaCur, ply + 1, historyWithThis, bufs, move)
          if score > bestScore then
            bestScore = score
            bestMove = move
          if score > alphaCur then alphaCur = score
          if alphaCur >= beta then
            cutoff = true
            recordKiller(ply, move)
            historyTable(MoveInt.fromIdx(move))(MoveInt.toIdx(move)) += depth * depth
            // Counter-move heuristic: when a quiet move refutes
            // `prevMove`, remember it as the canonical reply to
            // that opponent move. Skipped at the root and inside
            // YBWC fibers where `prevMove == NoKiller`.
            if counterMoveEnabled && prevMove != NoKiller then
              counterMoveTable(MoveInt.fromIdx(prevMove))(MoveInt.toIdx(prevMove)) = move
        }
        moveIndex += 1
        i -= 1

    val kind =
      if bestScore <= alpha then Kind.Upper
      else if bestScore >= beta then Kind.Lower
      else Kind.Exact
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
      prevMove: Int,
  ): Unit =
    val ttBest = tt.get(hash).flatMap(_.bestMove).fold(NoKiller)(MoveInt.encodeMove)
    val k0 = if ply < MaxPly then killer0(ply) else NoKiller
    val k1 = if ply < MaxPly then killer1(ply) else NoKiller
    val counter =
      if !counterMoveEnabled || prevMove == NoKiller then NoKiller
      else counterMoveTable(MoveInt.fromIdx(prevMove))(MoveInt.toIdx(prevMove))
    var i = 0
    while i < count do
      val m = moveBuf(i)
      val score = scoreMove(state, m, ttBest, k0, k1, counter)
      scoredOut(i) = MoveInt.pack(score, insertionIdx = i, move = m)
      i += 1
    java.util.Arrays.sort(scoredOut, 0, count)

  /** Per-move ordering score. Higher is tried first. Score buckets:
    *   - 1_000_000           TT bestMove
    *   -   100_000           winning/equal capture (MVV-LVA tiebreak)
    *                         — with SEE on, only SEE ≥ 0 captures
    *                         land here
    *   -    90_000           killer slot 0 (most recent)
    *   -    80_000           killer slot 1
    *   -    70_000           counter-move (refutation of `prevMove`)
    *   -        0..69_999    quiet — history-heuristic score (capped
    *                         at 69_999 so a hot history entry can't
    *                         sneak past the counter-move bucket)
    *   -   -99_999.. -1      losing capture (SEE < 0), bucket
    *                         `-100_000 + see_value` so e.g. RxB(-170)
    *                         scores -100_170, sorted just above the
    *                         worst possible losing trade and below
    *                         every quiet. Tried last. */
  private def scoreMove(
      state: GameState,
      move: Int,
      ttBest: Int,
      k0: Int,
      k1: Int,
      counter: Int,
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
          val mvvLva = 100_000 + victimVal * 10 - attackerVal
          if !seeEnabled then mvvLva
          else
            val seeVal = StaticExchange.see(state, move)
            if seeVal >= 0 then mvvLva
            // Losing captures sort below every quiet. Use
            // -100_000 + seeVal so worse SEE → lower position,
            // preserving relative order within the losing bucket.
            else -100_000 + seeVal
        case None =>
          if move == k0 then 90_000
          else if move == k1 then 80_000
          else if move == counter then 70_000
          else
            math.min(historyTable(MoveInt.fromIdx(move))(MoveInt.toIdx(move)), 69_999)

  /** Decode a LERF square index back to the cached [[Position]]
    * flyweight — no allocation. */
  private inline def positionAt(idx: Int): Position =
    Position(('a' + (idx % 8)).toChar, idx / 8 + 1)

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
