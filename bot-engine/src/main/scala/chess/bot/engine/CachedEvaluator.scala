package chess.bot.engine

import java.util.concurrent.ConcurrentHashMap

import chess.model.board.GameState
import chess.model.rules.Zobrist

/** Decorator that caches static-eval results per position hash.
  *
  * The α-β search bypasses the TT for leaf nodes (negamax returns
  * `leafScore(...)` directly when `depth <= 0`, no TT write), so
  * every leaf reaches the underlying evaluator afresh. At depth 4-6
  * the same position is often reached via different orderings —
  * transpositions — and each reach re-walks 690 features. Caching
  * the eval per Zobrist hash short-circuits that.
  *
  * This is the simpler form of "partial position lookup" the
  * chess-engine literature calls a *pawn hash* / *king safety
  * cache*: the canonical refactor splits features by which parts
  * of the board they depend on and caches each subsystem
  * separately. Full-position caching gives most of the hit-rate
  * benefit at a fraction of the implementation cost; pawn-hash
  * style decomposition is a refinement for later.
  *
  * Thread-safety: backed by `ConcurrentHashMap`, so safe under
  * LazySMP / YBWC parallel evaluation. The inner evaluator's
  * concurrency story is unchanged.
  *
  * Bounded by `maxEntries`. On overflow we drop every-other entry
  * (parity sweep, same eviction shape as `TranspositionTable`). */
final class CachedEvaluator private (
    inner: Evaluator,
    maxEntries: Int,
) extends Evaluator:

  // Stored as boxed Integer so `get` can return null on miss
  // without colliding with any legal Int eval value.
  private val cache = new ConcurrentHashMap[java.lang.Long, java.lang.Integer](maxEntries * 2)
  @volatile private var evictParity: Long = 0L

  override def evaluate(state: GameState): Int =
    val key = java.lang.Long.valueOf(Zobrist.hash(state))
    val cached = cache.get(key)
    if cached != null then cached.intValue
    else
      val score = inner.evaluate(state)
      cache.put(key, java.lang.Integer.valueOf(score))
      if cache.size > maxEntries then evict()
      score

  /** Sweep every key whose low bit matches the current parity,
    * then flip — same shape as the search TT's eviction. */
  private def evict(): Unit =
    val parity = evictParity
    val it = cache.keys()
    while it.hasMoreElements do
      val k = it.nextElement()
      if (k.longValue & 1L) == parity then cache.remove(k)
    evictParity = parity ^ 1L

object CachedEvaluator:

  /** Wrap an Evaluator with a position-eval cache of `maxEntries`
    * slots. Default 1M is ~12 MB of HashMap memory and gives a
    * high hit rate at depth 4-6 search. */
  def of(inner: Evaluator, maxEntries: Int = 1_000_000): CachedEvaluator =
    new CachedEvaluator(inner, maxEntries)
