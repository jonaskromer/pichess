package chess.bot.engine

import zio.UIO
import zio.ZIO

import chess.model.board.{GameState, Move}
import chess.model.piece.Color

/** Opening-book lookup for the search.
  *
  * The book stores moves keyed by position; in a real game, the
  * search consults it for the first few plies (where book theory
  * dominates strict α-β) and falls through to native search once the
  * position drifts out of book or the ply limit is reached.
  *
  * Returning `None` is always a safe answer — the search then runs as
  * if no book existed. So a slow / failing book lookup degrades to
  * "play it from scratch" rather than blocking the bot's move.
  */
trait OpeningBook:
  def lookup(state: GameState): UIO[Option[Move]]

object OpeningBook:

  /** Compute the ply count of `state` (zero-based: starting position
    * is ply 0; after 1.e4 it's ply 1; after 1...e5 it's ply 2). */
  def ply(state: GameState): Int =
    (state.fullmoveNumber - 1) * 2 +
      (if state.activeColor == Color.Black then 1 else 0)

  /** Always-None book — used by [[Search.alphaBeta]] as the default
    * when no real book has been wired. */
  val Empty: OpeningBook =
    new OpeningBook:
      def lookup(state: GameState): UIO[Option[Move]] = ZIO.none

  /** In-memory book keyed by Zobrist hash. Useful for tests and for
    * small hand-curated opening repertoires; production runs use the
    * DuckDB-backed adapter from `bot-data`. */
  def inMemory(entries: Map[Long, Move], maxPly: Int = 24): OpeningBook =
    new InMemoryBook(entries, maxPly)

  private final class InMemoryBook(
      entries: Map[Long, Move],
      maxPly: Int,
  ) extends OpeningBook:
    def lookup(state: GameState): UIO[Option[Move]] =
      if ply(state) >= maxPly then ZIO.none
      else ZIO.succeed(entries.get(chess.model.rules.Zobrist.hash(state)))
