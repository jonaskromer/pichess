package chess.bot.engine

import zio.*

import chess.model.board.{GameState, Move}

/** Search wrapper that delegates low-piece positions to an external
  * tablebase-backed oracle (typically Stockfish-with-`SyzygyPath`), and falls
  * back to the supplied `inner` search at high-piece counts.
  *
  * Conceptually: every move the bot makes is "pichess at depth N unless the
  * current position is in Syzygy range, in which case it's a TB-perfect
  * answer."
  *
  * Tradeoffs vs ideal in-search TB use:
  *   - We only probe at the *root* of [[bestMove]], not inside the search tree.
  *     A position that's one ply away from a winning 5-piece ending will see
  *     normal pichess eval at the leaf, not TB-resolved.
  *   - For positions just outside TB range (6 pieces), the bot uses normal
  *     pichess. So "TB augmentation" only kicks in once the game has simplified
  *     enough — typically the last ~10-30 plies of an endgame.
  *
  * Despite both caveats, this captures the practical effect for "playing
  * endgames perfectly once you reach them" — which is the usual reason you'd
  * ship Syzygy in the first place. Originally implemented in bot-train for
  * tournament play; moved to bot-engine so production / Lichess deployments can
  * also benefit when a Syzygy-backed UCI oracle is available at runtime.
  */
final class TbAugmentedSearch(
    inner: Search,
    tb: Search,
    pieceLimit: Int
) extends Search:
  override def bestMove(
      state: GameState,
      depth: Int,
      history: Set[Long] = Set.empty
  ): UIO[Option[Move]] =
    val pieces = state.board.occupancy.popCount
    if pieces <= pieceLimit then
      tb.bestMove(state, depth = 1, history).flatMap {
        case Some(m) => ZIO.some(m)
        case None    => inner.bestMove(state, depth, history)
      }
    else inner.bestMove(state, depth, history)

  /** Budgeted path (the live bot): same root-probe logic, but the fallback
    * keeps the caller's TIME BUDGET (otherwise wrapping in TB would silently
    * drop clock-aware time management down to a fixed-depth search). The TB
    * probe itself is O(1) at the root, so it doesn't consume the budget.
    */
  override def bestMoveWithBudget(
      state: GameState,
      budgetMillis: Long,
      history: Set[Long] = Set.empty,
      fallbackDepth: Int = 6
  ): UIO[Option[Move]] =
    val pieces = state.board.occupancy.popCount
    if pieces <= pieceLimit then
      tb.bestMove(state, depth = 1, history).flatMap {
        case Some(m) => ZIO.some(m)
        case None =>
          inner.bestMoveWithBudget(state, budgetMillis, history, fallbackDepth)
      }
    else inner.bestMoveWithBudget(state, budgetMillis, history, fallbackDepth)
