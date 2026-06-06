package chess.bot.train

import zio.*

import chess.bot.engine.Search
import chess.model.board.{GameState, Move}

/** Search wrapper that delegates low-piece positions to a Syzygy-
  * backed oracle (Stockfish-with-`SyzygyPath`-set), and falls back
  * to the supplied `inner` search at high-piece counts.
  *
  * Conceptually: every move the bot makes is "pichess at depth N
  * unless the current position is in Syzygy range, in which case
  * it's a TB-perfect answer."
  *
  * Why Stockfish-with-Syzygy and not a custom probe: writing a
  * pure-Scala Syzygy probe is a multi-day project (compression +
  * indexing tables); Stockfish ships TB support out of the box,
  * and we already speak UCI to it via [[StockfishSearch]].
  *
  * Tradeoffs vs ideal in-search TB use:
  *   - We only probe at the *root* of [[bestMove]], not inside the
  *     search tree. A position that's one ply away from a winning
  *     5-piece ending will see normal pichess eval at the leaf,
  *     not TB-resolved.
  *   - For positions just outside TB range (6 pieces), the bot
  *     uses normal pichess. So "TB augmentation" only kicks in
  *     once the game has simplified enough — typically the last
  *     ~10-30 plies of an endgame.
  *
  * Despite both caveats, this captures the practical effect for
  * "playing endgames perfectly once you reach them" — which is the
  * usual reason you'd ship Syzygy in the first place. */
final class TbAugmentedSearch(
    inner: Search,
    tb: Search,
    pieceLimit: Int,
) extends Search:
  override def bestMove(
      state: GameState,
      depth: Int,
      history: Set[Long] = Set.empty,
  ): UIO[Option[Move]] =
    val pieces = state.board.occupancy.popCount
    if pieces <= pieceLimit then
      // At depth 1 Stockfish-with-SyzygyPath returns the TB answer
      // immediately on probe hits (no real search needed). On a
      // probe miss (entry not in the bundled 3-4-5 set, e.g. a
      // promotion landing at piece-count 6 mid-search), Stockfish
      // falls back to its own depth-1 search — still a reasonable
      // root move. Either way, faster than pichess at depth `depth`.
      tb.bestMove(state, depth = 1, history).flatMap {
        case Some(m) => ZIO.some(m)
        case None    => inner.bestMove(state, depth, history)
      }
    else inner.bestMove(state, depth, history)
