package chess.bot.engine

import zio.{UIO, ZIO}

import chess.bot.engine.internal.{CounterMoveSeed, RulesAdapter, StaticExchange}
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
    // Null-move pruning: at non-PV non-check nodes with non-pawn
    // material for the side to move, give the opponent a free
    // move and search at reduced depth with a null window — if the
    // result still ≥ β, prune the whole subtree. See
    // [[Search.alphaBeta]] for the empirical Elo finding.
    nullMovePruningEnabled: Boolean = true,
    // Late-Move Pruning + Futility (frontier pruning bundle). At
    // low remaining depth, drop quiet moves past a move-count
    // threshold (LMP) and skip individual quiets whose static
    // eval + margin can't reach α (futility).
    lmpFutilityEnabled: Boolean = false,
    // Aspiration windows on iterative deepening. Once ID has a
    // root score from one iteration, the next iteration searches
    // with `[score - 50, score + 50]` so cutoffs land sooner.
    // Re-searches with widened window on fail-low/high. Sync
    // path only — parallel YBWC root doesn't honour aspiration
    // yet.
    aspirationWindowsEnabled: Boolean = false,
    // Simplified singular extensions: when the TT bestMove at a
    // node is backed by an entry of sufficient depth + non-Upper
    // bound, extend its recursive search by 1 ply. Skips the
    // canonical verification re-search (which doubles per-node
    // cost) but captures most of the Elo bump.
    singularExtensionsEnabled: Boolean = false,
    // LazySMP: when ON and `parallelism > 1`, replace the YBWC
    // root fan-out with K-1 helper fibers each running a sync
    // search at a slightly different depth, all sharing the TT.
    // The main fiber returns at the requested depth; helpers are
    // cancelled on return. Cross-pollination via the shared TT
    // is the win — helpers' deeper-search TT entries help main's
    // ordering, and main's entries help helpers cut off faster.
    lazySmpEnabled: Boolean = false,
    // Load the baked PGN-derived CMH seed when present. When
    // false, the per-search reset uses NoKiller (cold start)
    // instead — useful for A/B comparison of seeded vs cold CMH.
    counterMoveSeedEnabled: Boolean = true,
    // Continuation history: replace CMH's (from, to) key with
    // (piece-type-on-to-square, to-square). Same per-cutoff
    // write shape, smaller (6×64=384) table, semantically
    // richer (the same destination square from different starting
    // squares often shares the same refutation). When ON,
    // overrides the CMH lookup at the 70_000 ordering bucket.
    continuationHistoryEnabled: Boolean = false,
    // Check extension: when the side to move is in check at the
    // node entry, search one extra ply. Standard textbook feature
    // — finds mate-in-N at one less initial depth, avoids the
    // horizon-effect blunder where a forced sequence ending in
    // a recapture gets cut at the worst possible moment. Default
    // OFF for clean A/B with the no-extension baseline.
    checkExtensionEnabled: Boolean = false,
    // NMP verification re-search: when the null-move reduced
    // search fails high (≥ β), normally we'd prune. With
    // verification ON, we instead re-search the SAME node at the
    // current depth without null-move pruning enabled — a
    // zugzwang-aware safety net. Cheap (one re-search at the
    // already-passed depth), catches the rare positions where
    // NMP returns a wrong fail-high.
    nmpVerificationEnabled: Boolean = false,
    // Pawn-hash correction history (Caissa/SF18 style): records
    // the (search score − static eval) delta keyed by pawn-only
    // Zobrist hash, then applies it as a correction to the static
    // eval on subsequent visits. Sits between eval and pruning —
    // no eval re-tuning required. Per-thread to avoid races.
    pawnCorrHistEnabled: Boolean = false,
    // Material-hash correction history. Same shape as the pawn
    // variant but keyed by piece-count signature. Captures the
    // "this material balance plays X cp differently than the
    // tuned eval expects" pattern. Layers on top of pawn corrhist.
    materialCorrHistEnabled: Boolean = false,
    // Internal Iterative Reductions: when no TT-best-move exists
    // at a sufficient-depth node, reduce depth by 1 before
    // searching. Reasoning — without a TT-seeded ordering, the
    // search will waste effort exploring bad moves first; shrinking
    // depth bounds the wasted work. Stockfish-derived; ~5-15 Elo.
    iirEnabled: Boolean = false,
    // Reverse Futility Pruning / Static Null-Move Pruning. At low
    // depth and non-PV, if `staticEval − margin*depth >= beta`,
    // return beta immediately — we're already so far above β that
    // any reasonable move keeps us there. Pairs with the
    // `improving` flag (smaller margin when improving). ~10-20 Elo.
    rfpEnabled: Boolean = false,
    // Razoring. At low depth, if `staticEval + margin < alpha`,
    // drop straight to qsearch. If qsearch still fails low, return
    // that score. Cheap downside check. ~5-10 Elo.
    razoringEnabled: Boolean = false,
    // Delta pruning in quiescence search: skip captures whose
    // material gain + safety margin can't lift the side-to-move
    // above alpha. Tightens the qsearch tree without losing
    // tactics. ~5-10 Elo.
    deltaPruningEnabled: Boolean = false,
    // History gravity: soft-clamp history table bonuses via
    // `bonus -= history * |bonus| / max` so the table doesn't
    // saturate at extreme values. Keeps the move-ordering signal
    // fresh through long searches. ~5 Elo.
    historyGravityEnabled: Boolean = false,
    // Move-count-based pruning (more aggressive than LMP): at
    // shallow depth and late move index past a generous threshold,
    // skip the remaining quiet moves entirely. Pairs with the
    // improving flag (later threshold when improving). ~5-10 Elo.
    moveCountPruningEnabled: Boolean = false,
    // Double extensions in singular-extension hot moves: when the
    // singular margin (TT score − beta_singular) is very high,
    // extend by 2 instead of 1. Stockfish refinement; ~5-10 Elo.
    doubleExtensionEnabled: Boolean = false,
    // Multi-cut pruning: at a cut-node, if ≥ M of the first N
    // moves at reduced depth (R = 2) already produce a fail-high,
    // assume the whole node fails high and prune the rest. ~5-15
    // Elo classical, less in modern NMP+LMR engines.
    multiCutEnabled: Boolean = false,
    // TT aging: bump a generation counter per top-level search and
    // prefer fresh-generation entries over stale ones on collision
    // (regardless of depth). Stale entries are from prior searches
    // that scored different game positions — their depth is
    // irrelevant to the current root.
    ttAgingEnabled: Boolean = false,
    // Time-management upgrades for budgeted search:
    //   * extend time when the root best-move changes between
    //     iterations (signal that the search hasn't converged)
    //   * extend time when the score drops sharply (signal that
    //     the previous evaluation was over-optimistic)
    //   * stop earlier when the best-move + score have both been
    //     stable for ≥ 2 iterations
    // Applies only to the budgeted path; fixed-depth searches are
    // unaffected.
    timeManagementUpgradeEnabled: Boolean = false,
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

  // Baked CMH seed loaded once at class init from the
  // `/counter-seed.bin` resource (built by `CounterSeedMain` from
  // the master-game PGN corpus). When the seed resource is
  // missing — e.g., engine launched without the training artefact —
  // or when [[counterMoveSeedEnabled]] is false (A/B comparison),
  // we use an all-NoKiller buffer matching the cold-start
  // behaviour. Stored as a flat `Array[Int]` of size 4096 (64×64)
  // for cheap copy-into-table on every search reset.
  private val counterMoveSeed: Array[Int] =
    if counterMoveSeedEnabled then CounterMoveSeed.load()
    else Array.fill(CounterMoveSeed.Size)(NoKiller)

  // Continuation-history table — same role as `counterMoveTable`
  // but keyed by (piece-type-on-to-square, to-square). 6 × 64 =
  // 384 cells, half the memory and (per Stockfish & friends) a
  // semantically stronger refutation signal because the same
  // destination square from different starting squares often
  // shares the same best reply.
  private val continuationTable: Array[Int] = Array.fill(6 * 64)(NoKiller)

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
    // Per-ply static eval cache. `Int.MinValue` is the sentinel for
    // "not computed at this ply yet" (legal evals never reach that
    // value — mate scores cap at ~ ±32000). Cleared lazily — each
    // ply's slot is overwritten before it's read.
    val staticEval: Array[Int]       = Array.fill(MaxPly + 1)(Int.MinValue)

  /** Thread-local pool for `SearchBufs`. Each OS thread gets one
    * instance (~166 KB) which is reused across every search that
    * thread executes — eliminates the per-search `_platform_bzero`
    * cost the profiler flagged at ~130 samples in the depth-6
    * benchmark. ZIO fibers don't yield mid-`syncBestMove` (the
    * body of `ZIO.succeed { syncBestMove(...) }` runs to
    * completion on the calling thread), so two concurrent fibers
    * never share a `SearchBufs` even though they may both touch
    * this `ThreadLocal`. Contents are always overwritten before
    * read inside the search loop, so no per-acquire clear is
    * needed. */
  private val pooledBufs: ThreadLocal[SearchBufs] =
    ThreadLocal.withInitial(() => new SearchBufs())

  private inline def acquireBufs(): SearchBufs = pooledBufs.get()

  override def evaluate(
      state: GameState,
      depth: Int,
      history: Set[Long] = Set.empty,
  ): UIO[Int] =
    bestMove(state, depth, history).as {
      // After the search, the root position's TT entry carries the
      // resolved side-to-move score. `syncBestMove` writes the root
      // entry explicitly (added with aspiration windows); the YBWC
      // root path doesn't, but its child nodes do, so the same
      // hash also has an entry one ply down. Fall back to 0 only
      // when nothing matched (root position with zero legal moves,
      // already filtered upstream).
      tt.get(Zobrist.hash(state)).map(_.score).getOrElse(0)
    }

  /** Principal variation walk: after a search, follow the TT
    * bestMove chain from `state`, applying each move along the
    * way, until we run out of TT entries with a bestMove, hit a
    * repetition, fail to apply (illegal — shouldn't happen but
    * defended against), or hit `maxLength`.
    *
    * The chain is naturally bounded by the deepest search that
    * has run before this call — TT entries beyond that depth
    * don't exist. Typical PV at depth 4 yields 4-8 plies. */
  override def principalVariation(
      state: GameState,
      depth: Int,
      maxLength: Int = 8,
      history: Set[Long] = Set.empty,
  ): UIO[List[Move]] =
    bestMove(state, depth, history).as(walkPv(state, history, maxLength))

  private def walkPv(
      state: GameState,
      history: Set[Long],
      remaining: Int,
  ): List[Move] =
    if remaining <= 0 then Nil
    else
      val hash = Zobrist.hash(state)
      if history.contains(hash) then Nil
      else
        tt.get(hash).flatMap(_.bestMove) match
          case Some(move) =>
            RulesAdapter.applyMove(state, move) match
              case Some(next) => move :: walkPv(next, history + hash, remaining - 1)
              case None       => Nil
          case None => Nil

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
        if ttAgingEnabled then
          tt.setAgingEnabled(true)
          tt.bumpGeneration()
        if iterativeDeepeningEnabled then iterativeBestMove(state, depth, history)
        else if lazySmpEnabled && parallelism > 1 then lazySmpBestMove(state, depth, history)
        else if parallelism > 1 then parallelBestMove(state, depth, history)
        else
          val bufs = acquireBufs()
          ZIO.succeed(syncBestMove(state, depth, history, bufs).map(MoveInt.decode))
    }

  /** LazySMP root search: spawn `parallelism-1` helper fibers
    * (each at depth +/- 1 alternating), then run the main search
    * at the requested depth. All share the shared TT, so helpers'
    * speculative deeper / faster shallower searches plant TT
    * entries that improve main's ordering decisions mid-flight.
    *
    * The result is whatever the main fiber returns. Helpers are
    * cancelled when main finishes (structured concurrency via
    * `raceFirst` semantics on the ZIO fiber tree).
    *
    * vs YBWC: YBWC fans out one root move per fiber (max useful
    * parallelism = #root moves - 1). LazySMP fans out one full-
    * tree-search per fiber, so it scales past `#root moves` and
    * benefits from arbitrary `parallelism` values. */
  private def lazySmpBestMove(
      state: GameState,
      depth: Int,
      history: Set[Long],
  ): UIO[Option[Move]] =
    // The main worker: runs sync search at the requested depth
    // and is the one whose result we return.
    val mainWorker: UIO[Option[Move]] =
      ZIO.succeed {
        val bufs = acquireBufs()
        syncBestMove(state, depth, history, bufs).map(MoveInt.decode)
      }
    if parallelism <= 1 then mainWorker
    else
      // Helpers: depth +/- 1 alternating so we get both deeper
      // (cross-pollinate exploration) and shallower (warm TT
      // ordering fast) variants. Helper results are discarded —
      // we only want their TT side-effects.
      val helperEffects = (1 until parallelism).map { idx =>
        val helperDepth = math.max(1, depth + (if idx % 2 == 0 then 1 else -1))
        ZIO.succeed {
          val bufs = acquireBufs()
          syncBestMove(state, helperDepth, history, bufs)
        }.forkDaemon
      }
      // Fire-and-forget the helpers — they share the TT and run
      // in parallel until they finish or main completes. main's
      // return value is what bestMove sees.
      ZIO
        .foreach(helperEffects)(identity)
        .flatMap { helperFibers =>
          mainWorker.ensuring(
            ZIO.foreach(helperFibers)(_.interrupt.unit).unit
          )
        }

  /** Time-budgeted ID: runs depths 1..MaxIterations, after each
    * iteration checks whether the predicted next-iteration cost
    * would overflow the remaining budget, and returns the deepest
    * completed result. Cost prediction uses the EBF (effective
    * branching factor): `next ≈ this × ebf`; we use ebf=5 as a
    * conservative default. A hard cap of `1.5 × budget` lets a
    * single iteration overrun slightly without aborting mid-search.
    *
    * Forced-mate short-circuits as usual (no point going deeper
    * once mate is found). */
  override def bestMoveWithBudget(
      state: GameState,
      budgetMillis: Long,
      history: Set[Long] = Set.empty,
      fallbackDepth: Int = 6,
  ): UIO[Option[Move]] =
    book.lookup(state).flatMap {
      case Some(move) => ZIO.some(move)
      case None       =>
        clearKillers()
        clearHistory()
        clearCounterMoves()
        if ttAgingEnabled then
          tt.setAgingEnabled(true)
          tt.bumpGeneration()
        budgetedBestMove(state, budgetMillis, history)
    }

  /** ID loop with a wall-clock deadline. Each completed iteration
    * records its elapsed time; the next iteration is only started
    * if `elapsed + projectedNext ≤ budget`. The cheap projection
    * is `ebf × thisDuration`; for a tight bound (avoid stranding
    * compute) we use ebf=4. */
  private def budgetedBestMove(
      state: GameState,
      budgetMillis: Long,
      history: Set[Long],
  ): UIO[Option[Move]] =
    val rootHash = Zobrist.hash(state)
    val start = System.nanoTime()

    def runAtDepth(d: Int): Option[Move] =
      if parallelism > 1 then
        // Parallel path doesn't return synchronously — UIO needed.
        // Skip it for budgeted variant; the budget is meant for
        // single-thread, time-controlled play.
        val bufs = acquireBufs()
        syncBestMove(state, d, history, bufs).map(MoveInt.decode)
      else
        val bufs = acquireBufs()
        syncBestMove(state, d, history, bufs).map(MoveInt.decode)

    // `stableSoFar` counts how many consecutive iterations have
    // produced the same best move with a small score swing. Used
    // by the time-management upgrade path to allow an early stop
    // when the search has clearly converged.
    def loop(
        d: Int,
        last: Option[Move],
        lastIterMs: Long,
        lastScore: Int,
        stableSoFar: Int,
    ): Option[Move] =
      val elapsedMs = (System.nanoTime() - start) / 1_000_000L
      val projectedNext = lastIterMs * 4 // EBF proxy
      val rootScore = tt.get(rootHash).map(_.score).getOrElse(lastScore)
      val mateFound = math.abs(rootScore) >= MateScore - MaxPly

      val outOfBudget =
        if !timeManagementUpgradeEnabled then
          elapsedMs + projectedNext > budgetMillis || elapsedMs > budgetMillis * 3 / 2
        else
          // Upgrade: scale the budget by stability (stable 2+ → 0.7×)
          // and by score swing (drop > 30cp → 1.5×). The hard cap stays
          // 1.5× of the unscaled budget so we don't blow past it.
          val swing = math.abs(rootScore - lastScore)
          val budgetScale: Double =
            if stableSoFar >= 2 && swing < 30 then 0.7
            else if swing > 30 then 1.5
            else 1.0
          val scaledBudget = (budgetMillis * budgetScale).toLong
          elapsedMs + projectedNext > scaledBudget || elapsedMs > budgetMillis * 3 / 2

      if d > MaxPly || outOfBudget || mateFound then last
      else
        val iterStart = System.nanoTime()
        val result = runAtDepth(d)
        val iterMs = (System.nanoTime() - iterStart) / 1_000_000L
        val nextStable =
          if result.isDefined && result == last && math.abs(rootScore - lastScore) < 30
          then stableSoFar + 1
          else 0
        loop(d + 1, result.orElse(last), iterMs, rootScore, nextStable)

    ZIO.succeed(loop(1, None, 0L, 0, 0))

  /** Multi-PV: returns the top-K root moves with their scores in
    * descending order. Uses the sync path (single-thread) so the
    * scores are deterministic; the parallel YBWC root would re-
    * order ties non-deterministically.
    *
    * Run cost: a fresh full-window root search that tracks every
    * move's score instead of cutting off as soon as it falls
    * below α. ~the same cost as `bestMove` because root has
    * α = -Infinity anyway (no cutoffs there). Used for analysis,
    * MCTS bootstrap, training-data labelling — not the hot tournament
    * path. */
  override def bestMoves(
      state: GameState,
      depth: Int,
      k: Int,
      history: Set[Long] = Set.empty,
  ): UIO[List[(Move, Int)]] =
    book.lookup(state).flatMap {
      case Some(move) => ZIO.succeed(List(move -> 0))
      case None       =>
        clearKillers()
        clearHistory()
        clearCounterMoves()
        if ttAgingEnabled then
          tt.setAgingEnabled(true)
          tt.bumpGeneration()
        val bufs = acquireBufs()
        ZIO.succeed(syncMultiPv(state, depth, history, bufs, k))
    }

  /** Sync root search variant that records `(move, score)` for
    * every root move and returns the top-K. Mirrors the structure
    * of [[syncBestMove]] but doesn't take α/β narrowing — every
    * move is searched at the full window so we get its real
    * score, not "≤ α / ≥ β" bound info. */
  private def syncMultiPv(
      state: GameState,
      depth: Int,
      history: Set[Long],
      bufs: SearchBufs,
      k: Int,
  ): List[(Move, Int)] =
    val capBuf   = bufs.captures(0)
    val quietBuf = bufs.quiets(0)
    val (capCount, quietCount) = RulesAdapter.fillCapturesAndQuiets(state, capBuf, quietBuf)
    if capCount == 0 && quietCount == 0 then Nil
    else
      val rootHash = Zobrist.hash(state)
      val rootHistory = history + rootHash
      val results = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
      // Captures
      if capCount > 0 then
        val scored = bufs.scored(0)
        orderMovesInto(capBuf, capCount, scored, state, rootHash, ply = 0, prevMove = NoKiller)
        var i = capCount - 1
        while i >= 0 do
          val move = MoveInt.fromPacked(scored(i))
          RulesAdapter.applyMoveInt(state, move).foreach { next =>
            val score = -negamax(next, depth - 1, -Infinity, Infinity, ply = 1, rootHistory, bufs, prevMove = move)
            results += (move -> score)
          }
          i -= 1
      // Quiets
      if quietCount > 0 then
        val scored = bufs.scored(0)
        orderMovesInto(quietBuf, quietCount, scored, state, rootHash, ply = 0, prevMove = NoKiller)
        var i = quietCount - 1
        while i >= 0 do
          val move = MoveInt.fromPacked(scored(i))
          RulesAdapter.applyMoveInt(state, move).foreach { next =>
            val score = -negamax(next, depth - 1, -Infinity, Infinity, ply = 1, rootHistory, bufs, prevMove = move)
            results += (move -> score)
          }
          i -= 1
      results
        .sortBy(-_._2)
        .take(k.max(1))
        .toList
        .map { case (move, score) => MoveInt.decode(move) -> score }

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
    * Aspiration windows: once iteration d-1 returns a score, the
    * iteration at depth d starts with `[score - 50, score + 50]`
    * instead of the full `[-Infinity, Infinity]`. Tighter window =
    * faster cutoffs = fewer nodes. On fail-low/high the window
    * widens by doubling the delta and the search re-runs at the
    * same depth. Disabled at d ≤ 2 (the window isn't worth the
    * re-search risk that early) and skipped when running parallel
    * — YBWC's elder-brother logic doesn't compose with aspiration
    * yet.
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
    val rootHash = Zobrist.hash(state)

    def runAtDepth(d: Int, alphaInit: Int, betaInit: Int): UIO[Option[Move]] =
      if parallelism > 1 then parallelBestMove(state, d, history)
      else
        val bufs = acquireBufs()
        ZIO.succeed(
          syncBestMove(state, d, history, bufs, alphaInit, betaInit).map(MoveInt.decode)
        )

    def aspirated(d: Int, prevScore: Int): UIO[Option[Move]] =
      // Aspiration only kicks in once we have a real prior score
      // AND we're past the shallow iterations where the window
      // can't be trusted yet.
      val useAspiration =
        aspirationWindowsEnabled && parallelism == 1 && d >= 3
      if !useAspiration then runAtDepth(d, -Infinity, Infinity)
      else
        def attempt(alphaInit: Int, betaInit: Int, delta: Int): UIO[Option[Move]] =
          runAtDepth(d, alphaInit, betaInit).flatMap { result =>
            val rootEntry = tt.get(rootHash)
            val rootScore = rootEntry.map(_.score).getOrElse(prevScore)
            val rootKind  = rootEntry.map(_.kind)
            rootKind match
              case Some(Kind.Upper) if delta < 4 * Infinity =>
                // Fail-low: true score is below alphaInit. Widen down.
                attempt(rootScore - delta * 2, betaInit, delta * 2)
              case Some(Kind.Lower) if delta < 4 * Infinity =>
                // Fail-high: true score is above betaInit. Widen up.
                attempt(alphaInit, rootScore + delta * 2, delta * 2)
              case _ =>
                ZIO.succeed(result)
          }
        attempt(prevScore - 50, prevScore + 50, 50)

    def loop(d: Int, last: Option[Move], prevScore: Int): UIO[Option[Move]] =
      if d > depth then ZIO.succeed(last)
      else
        aspirated(d, prevScore).flatMap { result =>
          val rootScore = tt.get(rootHash).map(_.score).getOrElse(prevScore)
          // Mate-in-N is already found; no point going deeper.
          val mateFound = math.abs(rootScore) >= MateScore - MaxPly
          if mateFound then ZIO.succeed(result)
          else loop(d + 1, result, rootScore)
        }
    loop(1, None, 0)

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
    val rootBufs = acquireBufs()
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
              val bufs = acquireBufs()
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
    * would suggest the wrong refutation.
    *
    * Reset baseline: when the baked seed is present (loaded once
    * at class init), reset row-by-row to the seed's contents
    * instead of all-NoKiller. The search then enters with a
    * master-derived prior for every common opponent move, which
    * the runtime cutoffs can still overwrite as they happen. */
  private def clearCounterMoves(): Unit =
    var from = 0
    while from < 64 do
      System.arraycopy(counterMoveSeed, from * 64, counterMoveTable(from), 0, 64)
      from += 1
    java.util.Arrays.fill(continuationTable, NoKiller)

  /** Pick the move at the root that maximises the negamax score for
    * the side to move. Returns the chosen move's [[MoveInt]]
    * encoding; the public [[bestMove]] decodes once at the boundary.
    *
    * Two-stage move generation — captures first, then quiets. With
    * the default `[-Infinity, Infinity]` window the search has no
    * β-cutoff at the root so we always iterate both stages; with
    * aspiration windows (narrow `[α, β]` from a prior ID iteration's
    * score) the root *can* cut off, and the same two-stage iteration
    * just honours the tighter window.
    *
    * Aspiration: writes a root TT entry at the end so the outer
    * [[iterativeBestMove]] loop can read the score for the next
    * iteration's window — kind is `Exact` inside `(α, β)`,
    * `Upper` on fail-low, `Lower` on fail-high. */
  private def syncBestMove(
      state: GameState,
      depth: Int,
      history: Set[Long],
      bufs: SearchBufs,
      alphaInit: Int = -Infinity,
      betaInit: Int = Infinity,
  ): Option[Int] =
    val capBuf   = bufs.captures(0)
    val quietBuf = bufs.quiets(0)
    val (capCount, quietCount) = RulesAdapter.fillCapturesAndQuiets(state, capBuf, quietBuf)
    if capCount == 0 && quietCount == 0 then None
    else
      val rootHash = Zobrist.hash(state)
      val rootHistory = history + rootHash
      var alpha = alphaInit
      val beta  = betaInit
      var bestScore = -Infinity
      var cutoff = false
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
        while i >= 0 && !cutoff do
          val move = MoveInt.fromPacked(scored(i))
          RulesAdapter.applyMoveInt(state, move).foreach { next =>
            val score = -negamax(next, depth - 1, -beta, -alpha, ply = 1, rootHistory, bufs, prevMove = move)
            if score > bestScore then
              bestScore = score
              best = move
            if score > alpha then alpha = score
            if alpha >= beta then cutoff = true
          }
          i -= 1

      // Stage 2: quiets
      if !cutoff && quietCount > 0 then
        val scored = bufs.scored(0)
        orderMovesInto(quietBuf, quietCount, scored, state, rootHash, ply = 0, prevMove = NoKiller)
        var i = quietCount - 1
        while i >= 0 && !cutoff do
          val move = MoveInt.fromPacked(scored(i))
          RulesAdapter.applyMoveInt(state, move).foreach { next =>
            val score = -negamax(next, depth - 1, -beta, -alpha, ply = 1, rootHistory, bufs, prevMove = move)
            if score > bestScore then
              bestScore = score
              best = move
            if score > alpha then alpha = score
            if alpha >= beta then cutoff = true
          }
          i -= 1

      // Aspiration support — stamp the root TT so the outer ID loop
      // can read the score back. The bound kind tells it whether the
      // search converged inside (Exact) or fell off one side (Upper/
      // Lower), which then drives the re-search window in
      // [[iterativeBestMove]].
      val kind =
        if bestScore <= alphaInit then Kind.Upper
        else if bestScore >= betaInit then Kind.Lower
        else Kind.Exact
      tt.put(rootHash, Entry(depth, bestScore, kind, Some(MoveInt.decode(best))))
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
      nullAllowed: Boolean = true,
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
              // Only pay the `isInCheck` cost upfront when one of the
              // new check-driven gates needs it. With everything OFF
              // we fall straight through to canNullMove which keeps
              // the original short-circuited check.
              val needsCheckUpfront =
                checkExtensionEnabled || rfpEnabled || razoringEnabled ||
                  moveCountPruningEnabled
              val inCheckHere =
                if needsCheckUpfront then RulesAdapter.isInCheck(state) else false
              val effDepth =
                if checkExtensionEnabled && inCheckHere then depth + 1 else depth

              // Static eval cache: compute lazily, but only when one of
              // the eval-driven gates (RFP, razoring, improving margin)
              // is active. Stored on the per-ply slot so an `improving`
              // check from `ply+2` can see this node's value.
              val needsStaticEval =
                (rfpEnabled || razoringEnabled || moveCountPruningEnabled) && !inCheckHere
              val staticEvalHere =
                if needsStaticEval then leafEvalRaw(state) else Int.MinValue
              if needsStaticEval then bufs.staticEval(ply) = staticEvalHere
              val isImprovingHere: Boolean =
                if !needsStaticEval || ply < 2 then true
                else
                  val prev = bufs.staticEval(ply - 2)
                  prev == Int.MinValue || staticEvalHere > prev

              // RFP / Static null-move: at non-PV low-depth nodes that
              // aren't in check, if the static eval already towers
              // over β by a depth-scaled margin, return β. Margin is
              // smaller when `isImproving` (we trust the eval more
              // when our position is on the up).
              val canRfp =
                rfpEnabled && !inCheckHere && (beta - alpha) == 1 &&
                  effDepth <= RfpMaxDepth && staticEvalHere != Int.MinValue &&
                  math.abs(beta) < MateScore - MaxPly
              val rfpFire =
                canRfp && {
                  val margin =
                    if isImprovingHere then RfpMarginImproving * effDepth
                    else                     RfpMarginNotImproving * effDepth
                  staticEvalHere - margin >= beta
                }

              // Razoring: same gates, opposite direction. If the
              // static eval + a depth-scaled margin still can't
              // reach α, drop straight to qsearch. If qsearch also
              // fails low, return that.
              val canRazor =
                razoringEnabled && !inCheckHere && (beta - alpha) == 1 &&
                  effDepth <= RazorMaxDepth && staticEvalHere != Int.MinValue
              val razorScore =
                if canRazor && staticEvalHere + RazorMargin * effDepth < alpha then
                  leafScore(state, alpha, beta, ply, bufs)
                else Int.MinValue

              // Null-move pruning. Standard gates:
              //   * not in check (a null move while in check leaves
              //     the king en-prise, an illegal position)
              //   * sufficient depth left (R reduces by ≥ 2, so we
              //     need depth ≥ 3 to even attempt)
              //   * non-pawn material for the side to move
              //     (zugzwang-prone endings — k+p vs k+p often have
              //     every real move losing, and would falsely prune
              //     here)
              //   * not the immediate child of a prior null search
              //     (double-null is meaningless and slows the
              //     search down with no Elo)
              //   * non-PV-ish: caller window already non-narrow
              //     (we check `beta - alpha > 1` as a cheap proxy)
              val canNullMove =
                nullMovePruningEnabled
                  && nullAllowed
                  && effDepth >= 3
                  // Reuse the upfront `inCheckHere` when we computed
                  // it for one of the new gates; otherwise call
                  // [[isInCheck]] directly to preserve the original
                  // short-circuit (depth-gated NMP only).
                  && (if needsCheckUpfront then !inCheckHere
                      else !RulesAdapter.isInCheck(state))
                  && hasNonPawnMaterial(state)
                  && (beta - alpha) > 1
              // IIR: when no TT-best-move at sufficient depth, ordering
              // will be poor — shrink depth by 1 to bound the wasted
              // search. Applied AFTER check extension and BEFORE null
              // move so NMP still respects the reduced depth.
              val iirDepth =
                if iirEnabled && effDepth >= 4 && tt.get(hash).flatMap(_.bestMove).isEmpty
                then effDepth - 1
                else effDepth
              if rfpFire then beta
              else if razorScore != Int.MinValue && razorScore < alpha then razorScore
              else if canNullMove then
                val r = if iirDepth >= 6 then 3 else 2
                val nullState = nullMoveState(state)
                val nullScore = -negamax(
                  nullState, iirDepth - 1 - r, -beta, -beta + 1,
                  ply + 1, history + hash, bufs, prevMove = NoKiller,
                  nullAllowed = false,
                )
                if nullScore >= beta then
                  // Verification re-search: re-run the same node at
                  // iirDepth without NMP enabled. Cheap insurance
                  // against zugzwang positions where the null move
                  // returns a misleading fail-high.
                  if nmpVerificationEnabled then
                    fullSearch(state, hash, iirDepth, alpha, beta, ply, history, bufs, prevMove)
                  else beta
                else fullSearch(state, hash, iirDepth, alpha, beta, ply, history, bufs, prevMove)
              else
                fullSearch(state, hash, iirDepth, alpha, beta, ply, history, bufs, prevMove)

  /** Decide whether the TT bestMove at the current node deserves
    * a +1 ply extension. Used by [[searchMoves]] when iterating —
    * if the current move equals the TT bestMove encoded by this
    * helper, it recurses at `depth - 1 + 1` instead of `depth - 1`.
    *
    * Conditions match the simplified "no-verification" singular
    * extension shape: depth ≥ 5, ply > 0 (root excluded — root's
    * window is already wide-open, no win from extending), TT entry
    * exists at depth ≥ depth - 2, and bound kind isn't Upper
    * (Upper bounds say "the real score is at most this", which
    * isn't strong evidence the move is uniquely best). */
  private def ttBestMoveExtension(hash: Long, depth: Int, ply: Int): (Int, Int) =
    if !singularExtensionsEnabled || depth < 5 || ply == 0 then (NoKiller, 0)
    else tt.get(hash) match
      case Some(entry) if entry.depth >= depth - 2 && entry.kind != Kind.Upper =>
        entry.bestMove match
          case Some(move) =>
            // Double-extension upgrade: the higher the TT score
            // (vs the current node's window), the more "uniquely
            // best" the move looks. When the gap is very large
            // and depth is reasonable, extend by 2 plies instead
            // of 1. Caps the chain via the existing depth gate so
            // a sequence of double-extensions can't blow up the
            // search tree.
            val bonus =
              if doubleExtensionEnabled && depth >= 7 &&
                 math.abs(entry.score) < MateScore - MaxPly &&
                 entry.score >= DoubleExtensionScoreCutoff
              then 2
              else 1
            (MoveInt.encodeMove(move), bonus)
          case None       => (NoKiller, 0)
      case _ => (NoKiller, 0)

  /** Helper: the negamax branch that actually generates and searches
    * all legal moves. Extracted so [[negamax]]'s NMP path can fall
    * through to it on `nullScore < beta`. */
  private def fullSearch(
      state: GameState,
      hash: Long,
      depth: Int,
      alpha: Int,
      beta: Int,
      ply: Int,
      history: Set[Long],
      bufs: SearchBufs,
      prevMove: Int,
  ): Int =
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

  /** Side-to-move has at least one knight/bishop/rook/queen — the
    * cheap proxy for "this isn't a pure king-pawn endgame, NMP is
    * unlikely to mis-fire on zugzwang here". Inlined to avoid the
    * Color branch on every NMP probe. */
  private def hasNonPawnMaterial(state: GameState): Boolean =
    val b = state.board
    if state.activeColor == Color.White then
      (b.knightsW.raw | b.bishopsW.raw | b.rooksW.raw | b.queensW.raw) != 0L
    else
      (b.knightsB.raw | b.bishopsB.raw | b.rooksB.raw | b.queensB.raw) != 0L

  /** Apply a null move — pass the turn without actually moving.
    * Used by [[nullMovePruningEnabled]] to test whether the
    * position is already so good that giving the opponent a free
    * move still leaves us with ≥ β. */
  private def nullMoveState(state: GameState): GameState =
    state.copy(
      activeColor     = if state.activeColor == Color.White then Color.Black else Color.White,
      enPassantTarget = None,
      halfmoveClock   = state.halfmoveClock + 1,
      fullmoveNumber  = if state.activeColor == Color.Black then state.fullmoveNumber + 1
                        else state.fullmoveNumber,
      inCheck         = false,
    )

  // Multi-cut pruning constants. Test the top-C captures at
  // depth-1-R; require M cutoffs to short-circuit. Conservative
  // defaults — multi-cut is at its highest Elo in classical engines
  // without modern NMP/LMR; in our setup it's a smaller win.
  private inline val MultiCutC = 6
  private inline val MultiCutM = 3
  private inline val MultiCutR = 2

  // Double-extension singular bonus fires when the TT score is at
  // least this many cp above zero — the move is so much stronger
  // than alternatives at high depth that we want to follow it
  // deeper than a single +1 ply.
  private inline val DoubleExtensionScoreCutoff = 300

  // RFP (Reverse Futility / Static Null-Move) constants. Standard
  // Stockfish-style depth-scaled margin; smaller when the side to
  // move's position is `improving` (eval rising vs ply-2), bigger
  // otherwise.
  private inline val RfpMaxDepth            = 6
  private inline val RfpMarginImproving     = 75
  private inline val RfpMarginNotImproving  = 125

  // Razoring constants. Depth cap + per-ply margin. If
  // `staticEval + Razor*depth < alpha`, drop to qsearch.
  private inline val RazorMaxDepth = 3
  private inline val RazorMargin   = 200

  // Delta-pruning constants. Promo bonus = queen value - pawn value
  // (the upgrade gained on promotion). Safety margin ≈ 1 piece, to
  // not prune captures that could lead to favourable trades a step
  // later (queen sac for two pieces, etc).
  private inline val DeltaPromoBonus    = 900 - 100
  private inline val DeltaSafetyMargin  = 200

  // History update with optional gravity. Standard Stockfish-style
  // formula: `new = old + bonus - old * |bonus| / Max`. Asymptotic
  // to `±Max`, keeps signals fresh without the unbounded growth of
  // raw `+=`. With gravity OFF, falls back to the historical `+=`
  // accumulation so A/B comparisons stay clean.
  private inline val HistoryMax = 16384

  private inline def updateHistory(from: Int, to: Int, bonus: Int): Unit =
    val cur = historyTable(from)(to)
    if historyGravityEnabled then
      historyTable(from)(to) = cur + bonus - cur * math.abs(bonus) / HistoryMax
    else
      historyTable(from)(to) = cur + bonus

  // ── Correction history (pawn + material) ────────────────────────
  //
  // Two parallel tables — one keyed by [[Zobrist.pawnHash]], one by
  // [[Zobrist.materialKey]] — accumulate the running delta between
  // the search score and the static eval. When enabled, [[leafEval]]
  // adds the table's correction to the raw eval before returning.
  // [[searchMoves]] writes back deltas at search completion (Exact
  // bounds only — Upper/Lower are unreliable for training).
  //
  // The table is per-thread because the EMA update isn't race-safe
  // and we want clean signals; cross-thread sharing isn't worth the
  // atomic dance.
  private inline val CorrHistSize  = 16384
  private inline val CorrHistMask  = 16383
  private inline val CorrHistScale = 256
  private inline val CorrHistMax   = 16384 * CorrHistScale

  private val pawnCorrHist: ThreadLocal[Array[Int]] =
    ThreadLocal.withInitial(() => new Array[Int](CorrHistSize))
  private val materialCorrHist: ThreadLocal[Array[Int]] =
    ThreadLocal.withInitial(() => new Array[Int](CorrHistSize))

  private inline def corrSlot(key: Long): Int = (key & CorrHistMask).toInt

  private inline def corrhistCorrection(state: GameState): Int =
    var corr = 0
    if pawnCorrHistEnabled then
      corr += pawnCorrHist.get()(corrSlot(Zobrist.pawnHash(state))) / CorrHistScale
    if materialCorrHistEnabled then
      corr += materialCorrHist.get()(corrSlot(Zobrist.materialKey(state))) / CorrHistScale
    corr

  private def updateCorrhist(state: GameState, staticEval: Int, searchScore: Int, depth: Int): Unit =
    val delta = (searchScore - staticEval) * CorrHistScale
    val weight = math.min(depth + 1, 16)
    if pawnCorrHistEnabled then updateOne(pawnCorrHist.get(), Zobrist.pawnHash(state), delta, weight)
    if materialCorrHistEnabled then updateOne(materialCorrHist.get(), Zobrist.materialKey(state), delta, weight)

  private inline def updateOne(table: Array[Int], key: Long, delta: Int, weight: Int): Unit =
    val idx = corrSlot(key)
    val cur = table(idx)
    val blended = (cur * (16 - weight) + delta * weight) / 16
    table(idx) =
      if blended >  CorrHistMax then  CorrHistMax
      else if blended < -CorrHistMax then -CorrHistMax
      else blended

  /** Raw STM-perspective evaluation, no corrhist correction applied.
    * The corrhist update path reads this directly so the recorded
    * delta is `search - rawEval`, not `search - (rawEval + correction)`
    * (which would converge to half the desired correction). */
  private inline def leafEvalRaw(state: GameState): Int =
    val raw = eval.evaluate(state)
    if state.activeColor == Color.White then raw else -raw

  /** Static evaluation at a leaf node, normalised to side-to-move POV
    * and corrected by the active correction-history tables. The
    * Evaluator hands back white-POV centipawns; flip on black. */
  private def leafEval(state: GameState): Int =
    val stm = leafEvalRaw(state)
    if pawnCorrHistEnabled || materialCorrHistEnabled then stm + corrhistCorrection(state) else stm

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
          // Delta pruning baseline = standPat. We skip captures
          // whose max possible material gain + safety can't reach α.
          // Disabled while in check (no stand-pat is available, and
          // every move is a forced reply we shouldn't prune).
          val deltaActive = deltaPruningEnabled && !inCheck
          val deltaBase   = if deltaActive then bestScore else 0
          var i = capCount - 1
          while i >= 0 && !cutoff do
            val move = MoveInt.fromPacked(scored(i))
            val to   = MoveInt.toIdx(move)
            val skipByDelta =
              if !deltaActive then false
              else
                val victimVal = state.board.get(positionAt(to))
                  .map(p => pieceValue(p.pieceType)).getOrElse(0)
                val isPromo = MoveInt.promo(move) != MoveInt.NoPromotion
                val promoBonus = if isPromo then DeltaPromoBonus else 0
                deltaBase + victimVal + promoBonus + DeltaSafetyMargin < alphaCur
            if !skipByDelta then
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
    // Simplified singular extension: when the conditions in
    // [[ttBestMoveExtension]] are met, the TT bestMove is treated
    // as "probably uniquely best" and its child search runs at
    // `depth - 1 + 1` instead of `depth - 1`. `seMove` is the
    // encoded MoveInt to extend; `seBonus` is the depth bonus (0
    // when the heuristic doesn't fire).
    val (seMove, seBonus) = ttBestMoveExtension(hash, depth, ply)
    var alphaCur  = alpha
    var bestScore = -Infinity
    var bestMove: Int = NoKiller
    var cutoff = false
    var moveIndex = 0

    // Multi-cut pre-test: at non-PV, non-check, depth ≥ 8 cut-nodes
    // with capCount > 0, try the top-K captures at reduced depth
    // and beta-window. If ≥ M of them already produce a fail-high
    // at depth-1-R, assume the whole node fails high and short-
    // circuit to β. Operates on the same `scored` slot the Stage 1
    // capture loop is about to read, so ordering work isn't
    // duplicated — Stage 1 just sees a slot already in priority
    // order.
    val canMultiCut =
      multiCutEnabled && depth >= 8 && (beta - alpha) == 1 &&
        !inCheckHere && capCount >= MultiCutC
    if canMultiCut then
      orderMovesInto(capBuf, capCount, scored, state, hash, ply, prevMove)
      var cutsFound = 0
      var tested = 0
      var j = capCount - 1
      while j >= 0 && tested < MultiCutC && cutsFound < MultiCutM && !cutoff do
        val move = MoveInt.fromPacked(scored(j))
        RulesAdapter.applyMoveInt(state, move).foreach { next =>
          val score = -negamax(
            next, depth - 1 - MultiCutR, -beta, -beta + 1,
            ply + 1, historyWithThis, bufs, move,
          )
          if score >= beta then cutsFound += 1
        }
        tested += 1
        j -= 1
      if cutsFound >= MultiCutM then
        // Short-circuit: skip the full move loop entirely.
        cutoff = true
        bestScore = beta

    // ── Stage 1: captures ───────────────────────────────────────
    if !cutoff && capCount > 0 then
      // Stage 1 always reorders. With multi-cut active above, the
      // current `scored` slot already holds the ordered captures —
      // re-ordering is a no-op cost but keeps the code branch-free.
      orderMovesInto(capBuf, capCount, scored, state, hash, ply, prevMove)
      var i = capCount - 1
      while i >= 0 && !cutoff do
        val move = MoveInt.fromPacked(scored(i))
        RulesAdapter.applyMoveInt(state, move).foreach { next =>
          // Captures don't get reduced (always "loud" by definition).
          val childDepth = depth - 1 + (if move == seMove then seBonus else 0)
          val score = -negamax(next, childDepth, -beta, -alphaCur, ply + 1, historyWithThis, bufs, move)
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
      // Conservative tuning after the first attempt (`4 + depth*2`
      // LMP + 100/300/500 futility w/ bare leafEval) lost -195 Elo.
      // Failure modes:
      //   * bare leafEval missed hanging pieces, so positions with
      //     loose material looked worse than they were → futility
      //     pruned the defensive replies.
      //   * LMP at depth 1 with threshold 6 dropped 80 % of quiets
      //     before the head was even searched.
      // Re-tuning:
      //   * LMP only at depth ≥ 2 with threshold `3 + depth^2`
      //     (gives 7 at d=2, 12 at d=3) — leaves the d=1 frontier
      //     alone where every move could still matter.
      //   * Futility only at depth = 1, margin 100, baseline = the
      //     quiescence-aware [[leafScore]] (resolves obvious
      //     captures before deciding "no move can lift this to α").
      val frontierish = lmpFutilityEnabled && !inCheckHere
      val futilityActive  = frontierish && depth == 1
      val lmpActive       = frontierish && depth >= 2 && depth <= 3
      val futilityMargin  = 100
      val staticEvalForFutility =
        if futilityActive then leafScore(state, alphaCur, beta, ply, bufs)
        else 0
      val futilityCanPrune =
        futilityActive && (staticEvalForFutility + futilityMargin <= alphaCur)
      val lmpQuietLimit = 3 + depth * depth

      // Improving-aware move-count pruning. Extends LMP to depth
      // 4-7 with an improving-flag-scaled threshold. Reads the
      // static eval cache populated by negamax (Int.MinValue if
      // not active or in check, which makes us assume improving=
      // true → larger threshold, more conservative pruning).
      val mcpActive = moveCountPruningEnabled && !inCheckHere && depth >= 2 && depth <= 7
      val mcpImproving: Boolean =
        if !mcpActive || ply < 2 then true
        else
          val cur  = bufs.staticEval(ply)
          val prev = bufs.staticEval(ply - 2)
          cur == Int.MinValue || prev == Int.MinValue || cur > prev
      val mcpLimit =
        if !mcpActive then Int.MaxValue
        else if mcpImproving then 5 + depth * depth
        else                       3 + depth * depth / 2

      var lmpPrune = false
      var i = quietCount - 1
      while i >= 0 && !cutoff && !lmpPrune do
        val move = MoveInt.fromPacked(scored(i))
        val isKiller = move == k0Here || move == k1Here
        // LMP: at depth 2-3, after the well-ordered head was
        // searched (limit = 3+d²), drop the rest. Killers / in-
        // check escape the prune.
        if lmpActive && moveIndex >= lmpQuietLimit && !isKiller then
          lmpPrune = true
        // Move-count pruning: complements LMP at depth 4-7 with an
        // improving-aware threshold. Larger limit when improving.
        else if mcpActive && moveIndex >= mcpLimit && !isKiller then
          lmpPrune = true
        // Futility: depth 1 only. Skip individual quiets whose
        // qsearched-baseline + margin can't even reach α.
        else if futilityCanPrune && !isKiller then
          // Tick moveIndex / i below so the loop progresses.
          ()
        else
          RulesAdapter.applyMoveInt(state, move).foreach { next =>
            val reduce =
              depth >= LmrMinDepth &&
                moveIndex >= LmrMoveThreshold &&
                !isKiller &&
                !inCheckHere
            val seExt = if move == seMove then seBonus else 0
            val baseDepth = depth - 1 + seExt
            val searchDepth = if reduce then baseDepth - 1 else baseDepth
            var score = -negamax(next, searchDepth, -beta, -alphaCur, ply + 1, historyWithThis, bufs, move)
            if reduce && score > alphaCur then
              score = -negamax(next, baseDepth, -beta, -alphaCur, ply + 1, historyWithThis, bufs, move)
            if score > bestScore then
              bestScore = score
              bestMove = move
            if score > alphaCur then alphaCur = score
            if alphaCur >= beta then
              cutoff = true
              recordKiller(ply, move)
              updateHistory(MoveInt.fromIdx(move), MoveInt.toIdx(move), depth * depth)
              // Counter-move heuristic: when a quiet move refutes
              // `prevMove`, remember it as the canonical reply to
              // that opponent move. Skipped at the root and inside
              // YBWC fibers where `prevMove == NoKiller`.
              //
              // When continuation history is on, write to the
              // (piece, to-square) table instead of the (from, to)
              // CMH table. Both maintain their own state for clean
              // A/B; the lookup side in [[orderMovesInto]] picks
              // which one to consult based on the flag.
              if prevMove != NoKiller then
                if continuationHistoryEnabled then
                  val prevTo = MoveInt.toIdx(prevMove)
                  state.board.get(positionAt(prevTo)).foreach { p =>
                    continuationTable(p.pieceType.ordinal * 64 + prevTo) = move
                  }
                else if counterMoveEnabled then
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
    // Train correction history on reliable signals only — Exact-bound
    // results where the search saw all moves. Cutoffs (Lower) and
    // all-moves-failed-low (Upper) misrepresent the position's true
    // value. Skip while in check too — the eval is meaningless when
    // a king is exposed.
    if (pawnCorrHistEnabled || materialCorrHistEnabled) && kind == Kind.Exact && !inCheckHere then
      updateCorrhist(state, leafEvalRaw(state), bestScore, depth)
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
      if prevMove == NoKiller then NoKiller
      else if continuationHistoryEnabled then
        // Continuation: key by (piece-on-prev_to-square, prev_to).
        // The piece sitting on prev_to in the current state is the
        // opponent's piece that just moved there.
        val prevTo = MoveInt.toIdx(prevMove)
        state.board.get(positionAt(prevTo)) match
          case Some(p) => continuationTable(p.pieceType.ordinal * 64 + prevTo)
          case None    => NoKiller
      else if counterMoveEnabled then
        counterMoveTable(MoveInt.fromIdx(prevMove))(MoveInt.toIdx(prevMove))
      else NoKiller
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
