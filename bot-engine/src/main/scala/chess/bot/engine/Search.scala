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
  ): Search =
    new AlphaBetaSearch(eval, TranspositionTable.inMemory(maxTtEntries), book)

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
