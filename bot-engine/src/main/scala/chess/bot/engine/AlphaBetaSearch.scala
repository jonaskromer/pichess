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
        else if lazySmpEnabled && parallelism > 1 then lazySmpBestMove(state, depth, history)
        else if parallelism > 1 then parallelBestMove(state, depth, history)
        else
          val bufs = new SearchBufs
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
        val bufs = new SearchBufs
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
          val bufs = new SearchBufs
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
        val bufs = new SearchBufs
        syncBestMove(state, d, history, bufs).map(MoveInt.decode)
      else
        val bufs = new SearchBufs
        syncBestMove(state, d, history, bufs).map(MoveInt.decode)

    def loop(d: Int, last: Option[Move], lastIterMs: Long): Option[Move] =
      val elapsedMs = (System.nanoTime() - start) / 1_000_000L
      val projectedNext = lastIterMs * 4 // EBF proxy
      val outOfBudget = elapsedMs + projectedNext > budgetMillis || elapsedMs > budgetMillis * 3 / 2
      val rootScore = tt.get(rootHash).map(_.score).getOrElse(0)
      val mateFound = math.abs(rootScore) >= MateScore - MaxPly
      if d > MaxPly || outOfBudget || mateFound then last
      else
        val iterStart = System.nanoTime()
        val result = runAtDepth(d)
        val iterMs = (System.nanoTime() - iterStart) / 1_000_000L
        loop(d + 1, result.orElse(last), iterMs)

    ZIO.succeed(loop(1, None, 0L))

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
        val bufs = new SearchBufs
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
        val bufs = new SearchBufs
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
                  && depth >= 3
                  && !RulesAdapter.isInCheck(state)
                  && hasNonPawnMaterial(state)
                  && (beta - alpha) > 1
              if canNullMove then
                val r = if depth >= 6 then 3 else 2
                val nullState = nullMoveState(state)
                val nullScore = -negamax(
                  nullState, depth - 1 - r, -beta, -beta + 1,
                  ply + 1, history + hash, bufs, prevMove = NoKiller,
                  nullAllowed = false,
                )
                if nullScore >= beta then beta
                else fullSearch(state, hash, depth, alpha, beta, ply, history, bufs, prevMove)
              else
                fullSearch(state, hash, depth, alpha, beta, ply, history, bufs, prevMove)

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
          case Some(move) => (MoveInt.encodeMove(move), 1)
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

    // ── Stage 1: captures ───────────────────────────────────────
    if capCount > 0 then
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
              historyTable(MoveInt.fromIdx(move))(MoveInt.toIdx(move)) += depth * depth
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
