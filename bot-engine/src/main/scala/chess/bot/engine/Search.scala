package chess.bot.engine

import zio.UIO

import chess.model.board.{GameState, Move}

/** Find the best move for the side to move in `state`.
  *
  * The public surface is intentionally tiny — a search is "give me a
  * state and a depth, get back a move". Implementations are free to
  * choose how to find that move (α-β, iterative-deepening, MCTS, …);
  * Phase 1 ships [[Search.alphaBeta]], a fixed-depth negamax search
  * with a transposition table.
  *
  * Returns `None` only when `state` has no legal moves (checkmate or
  * stalemate). At any legal position the search will always pick *a*
  * move — even if every option loses, it picks the one that loses the
  * latest / by the least material.
  *
  * `history` is the set of Zobrist hashes of positions that have
  * already occurred in the game leading up to `state` (excluding
  * `state` itself). The search treats any position whose Zobrist
  * appears in `history` (or on the search path so far) as an
  * immediate draw — engines normally collapse 2- and 3-fold this way
  * because the opponent can claim the repetition. Pass `Set.empty`
  * (the default) when the caller doesn't track history; tests + the
  * standalone engine entry points use that path.
  */
trait Search:
  def bestMove(
      state: GameState,
      depth: Int,
      history: Set[Long] = Set.empty,
  ): UIO[Option[Move]]

  /** Optional multi-PV: returns the top-K root moves sorted by
    * score descending. Implementations that don't track multi-PV
    * may return a singleton list wrapping `bestMove`'s result; for
    * analysis use cases (UI, training-data dumps, MCTS bootstrap)
    * the search-side override returns the real top-K.
    *
    * Default behaviour falls through to `bestMove` so existing
    * impls still compile. */
  def bestMoves(
      state: GameState,
      depth: Int,
      k: Int,
      history: Set[Long] = Set.empty,
  ): UIO[List[(Move, Int)]] =
    bestMove(state, depth, history).map(_.toList.map(m => m -> 0))

  /** Time-budgeted search. Runs iterative deepening until the
    * deeper iteration is predicted to overflow `budgetMillis`, then
    * returns the deepest completed iteration's best move.
    *
    * Default falls through to `bestMove(state, fallbackDepth)` so
    * implementations without time management still compile;
    * production callers should target the override on
    * `AlphaBetaSearch`. */
  def bestMoveWithBudget(
      state: GameState,
      budgetMillis: Long,
      history: Set[Long] = Set.empty,
      fallbackDepth: Int = 6,
  ): UIO[Option[Move]] =
    bestMove(state, fallbackDepth, history)

object Search:

  /** Sentinel score for "the position is mate, lost from this side's
    * perspective". Real mate scores are `MateScore - ply` so the search
    * prefers shorter mates (high ply = late = low score) and avoids
    * being mated as long as possible (low ply = soon = highly negative).
    */
  inline val MateScore = 100_000

  /** Default α and β bounds — anything outside this is a mate score. */
  inline val Infinity = 1_000_000

  /** Negamax α-β with transposition-table lookups.
    *
    * Search proceeds as classical α-β: at each node, try every legal
    * move, recurse with `(-β, -α)` because we're flipping sides, take
    * the best score from this side's perspective. The TT shortcuts
    * positions we've already searched to ≥ this depth.
    *
    * Move ordering: when a TT hit gives a `bestMove`, try that one
    * first — it's the move most likely to cause a β-cutoff, which
    * collapses the rest of the tree at this node. Captures and other
    * "loud" moves get further reordering in later phases (MVV-LVA,
    * killer moves, history heuristic).
    *
    * @param maxTtEntries cap on the in-memory TT. ~1M is a few-MB
    *                     footprint and avoids unbounded growth across
    *                     multi-game sessions.
    */
  def alphaBeta(
      eval: Evaluator,
      book: OpeningBook = OpeningBook.Empty,
      maxTtEntries: Int = 1_000_000,
      parallelism: Int = 1,
      // OFF by default — the textbook +20-40 Elo CMH benefit didn't
      // materialise for this engine at depth 4: a 200-game head-to-
      // head v8-CMH vs v8-noCMH measured ΔElo = -20.9 (39-51-110,
      // score 47.0%, parallelism 8). Likely TT + killers + history
      // + LMR already do the cutoff work CMH would add, and giving
      // counter-moves a higher bucket (70_000) than history dethrones
      // a better-ranked quiet move. Kept as a flag (not deleted) so
      // a future iterative-deepening / deeper search can re-test it
      // cheaply — the table cost is one 64×64 Int array.
      counterMoveEnabled: Boolean = false,
      // ON by default — a 200-game v8+quiescence vs v8-bare head-to-
      // head at depth 4 parallelism 8 measured ΔElo = **+179.5**
      // (108-13-79, score 73.8%). The horizon effect was biting hard;
      // quiescence escapes it by recursing on captures from the leaf
      // until a stable position is reached. Cost: ~5.3× slower (443s
      // vs 83s baseline for the 200-game tournament with quiescence
      // on only the challenger side). Worth the time per move.
      quiescenceEnabled: Boolean = true,
      // ON — at depth 4 SEE is neutral (-3.5 Elo, in noise), but a
      // 100-game depth-6 v8+Q+NMP+SEE vs v8+Q+NMP run measured
      // ΔElo = +17.4 (22-17-61, score 52.5%). The depth-4 finding
      // was correct (quiescence already cleans losing captures at
      // shallow search) but the deeper-search case is what matters
      // in production: better main-search ordering at depth 6+
      // wins back the lost cutoffs SEE prevents.
      seeEnabled: Boolean = true,
      // OFF — a 200-game v8+Q+ID vs v8+Q head-to-head at depth 4
      // parallelism 8 (with depth-preferred TT replacement, which
      // is a separate bugfix) measured ΔElo = -24.4 (35-49-116,
      // score 46.5%). At fixed depth, ID's TT-warming benefit is
      // outweighed by (a) the extra shallow-search time and (b)
      // non-determinism in parallel search picking a slightly
      // worse move at score-ties when the TT contents differ.
      // Kept as a flag so a real time-budget variant can layer
      // on top of it later — without ID you can't gracefully
      // trade depth for budget.
      iterativeDeepeningEnabled: Boolean = false,
      // ON — a 200-game v8+Q+NMP vs v8+Q head-to-head at depth 4
      // parallelism 8 measured ΔElo = +15.6 (46-37-117, score
      // 52.3%) AND 15% faster wall time (1009s vs 1184s baseline).
      // Borderline-positive Elo plus a real speedup is a clear
      // ship-on. Smaller than textbook +50-80 because quiescence
      // already prunes many tactical lines NMP would prune;
      // R=2/3 reduction is conservative.
      nullMovePruningEnabled: Boolean = true,
      // OFF — original tuning was -195 Elo at depth 4; conservative
      // re-tune (LMP gated to d=2-3 with threshold `3+d²`, futility
      // only at d=1 with qsearched baseline + 100 cp margin) cut
      // that to -52.5 Elo. Pruning quiet moves is the wrong call
      // for this engine at depth 4 — even the well-ordered quiet
      // head occasionally hides a refutation that LMP drops. Kept
      // as a flag for future depth-6+ re-test where each move
      // costs more.
      lmpFutilityEnabled: Boolean = false,
      // Aspiration windows on iterative deepening. Defaults OFF
      // after the depth-4 par=1 A/B (challenger ID+asp vs champion
      // no-ID) measured ΔElo = -27.9 (1-9-90, 46%) — essentially
      // the same as ID alone at d4 (-24.4), so aspiration doesn't
      // recover the shallow-iteration overhead at this depth. The
      // window-narrowing benefit is expected to grow with depth;
      // re-test at depth 6+ before flipping ON. Sync path only —
      // YBWC parallel root doesn't honour aspiration yet.
      aspirationWindowsEnabled: Boolean = false,
      // ON — a 100-game v8+Q+NMP+SE vs v8+Q+NMP head-to-head at
      // depth 6 par=8 measured ΔElo = +20.9 (22-16-62, 53.0%)
      // with essentially identical wall time. Gate `depth ≥ 5`
      // means zero effect at depth 4, +20 at d6+; pure upside.
      // Simplified form: skip the canonical verification re-search,
      // just blindly extend the TT bestMove when entry depth +
      // bound kind suggest it's uniquely best.
      singularExtensionsEnabled: Boolean = true,
      // ON by default — the baked `/counter-seed.bin` (built once
      // by `CounterSeedMain` from the PGN corpus) prefills the CMH
      // table at every search reset with the modal master-game
      // reply per (prev_from, prev_to). Falls back to cold-start
      // (NoKiller everywhere) when the resource isn't packaged.
      counterMoveSeedEnabled: Boolean = true,
      // OFF pending A/B. Continuation history replaces the
      // (from, to)-keyed CMH table with a (piece-on-to-square,
      // to-square) one — semantically richer because the same
      // destination from different sources often shares a
      // refutation. Override: when ON, the CMH bucket at 70_000
      // uses the continuation table instead of the CMH table.
      continuationHistoryEnabled: Boolean = false,
      // OFF pending A/B. LazySMP: parallelism-1 helper fibers run
      // searches at depth +/- 1, sharing the TT with the main
      // worker. Replaces YBWC when both `lazySmpEnabled` and
      // `parallelism > 1`.
      lazySmpEnabled: Boolean = false,
  ): Search =
    new AlphaBetaSearch(
      eval, TranspositionTable.inMemory(maxTtEntries), book,
      parallelism, counterMoveEnabled, quiescenceEnabled, seeEnabled,
      iterativeDeepeningEnabled, nullMovePruningEnabled,
      lmpFutilityEnabled, aspirationWindowsEnabled, counterMoveSeedEnabled,
      continuationHistoryEnabled, singularExtensionsEnabled,
      lazySmpEnabled,
    )

  /** Test-only factory that lets a caller inject the [[TranspositionTable]]
    * instance, so a test can pre-seed entries to exercise the move-
    * ordering and α/β-cutoff branches that don't fire on shallow
    * searches starting from an empty cache. Not exposed publicly — the
    * production [[alphaBeta]] factory owns its TT.
    */
  private[engine] def alphaBetaWith(
      eval: Evaluator,
      tt: TranspositionTable,
      book: OpeningBook = OpeningBook.Empty,
  ): Search = new AlphaBetaSearch(eval, tt, book)
