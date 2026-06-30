package chess.bot.engine

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/** A process-global cap on concurrent **search-helper** threads, shared across
  * every per-game [[Search]] so they don't oversubscribe the CPU when the bot
  * plays several games at once.
  *
  * The contract is deliberately asymmetric:
  *   - A search's **main worker always runs** — it never blocks on the budget,
  *     so an essential move is never delayed behind speculative work (no flag
  *     risk). It does [[enter]]/[[leave]] so the budget KNOWS how many mains are
  *     live and keeps a core for each of them.
  *   - **LazySMP helpers** grab spare permits **non-blocking**
  *     ([[acquireHelpers]]): they only spawn on cores not reserved for a main.
  *
  * So: one active game → it grabs all spare cores (full multi-core LazySMP); N
  * active games → each main reserves a core and the helpers split the rest, so
  * the extra mains run main-only instead of oversubscribing. Adaptive, bounded,
  * never blocks.
  */
final class ParallelismBudget(val permits: Int):

  private val sem = new Semaphore(math.max(0, permits))
  private val active = new AtomicInteger(0)

  /** Register a live main worker. The budget reserves a core per concurrent
    * main, so the helpers handed to any one search leave room for the others'
    * mains. Always pair with [[leave]]. */
  def enter(): Unit = active.incrementAndGet()

  /** Deregister a main worker (its search has finished). */
  def leave(): Unit = active.decrementAndGet()

  /** Grab up to `maxHelpers` spare permits without blocking — returns how many
    * were actually acquired (`0..maxHelpers`). The caller's main worker runs
    * regardless. Always pair the returned count with [[release]].
    */
  def acquireHelpers(maxHelpers: Int): Int =
    // Keep a core for every OTHER live main worker. `permits` already reserves
    // one core (for this search's main + I/O); each additional concurrent main
    // takes one more, so the helpers split only what's genuinely spare.
    val cap = math.min(maxHelpers, permits - math.max(0, active.get() - 1))
    var n = 0
    while n < cap && sem.tryAcquire() do n += 1
    n

  /** Return `n` previously-acquired helper permits. */
  def release(n: Int): Unit = if n > 0 then sem.release(n)

  /** Currently-free permits (diagnostics / tests). */
  def available: Int = sem.availablePermits()

object ParallelismBudget:

  /** No helpers — every search runs single-threaded (the default; LazySMP off).
    */
  val Single: ParallelismBudget = new ParallelismBudget(0)

  /** A budget sized to the machine, reserving `reserve` cores for the main
    * workers + I/O. So total search threads ≈ availableProcessors.
    */
  def ofCores(reserve: Int = 1): ParallelismBudget =
    new ParallelismBudget(
      math.max(0, Runtime.getRuntime.availableProcessors() - reserve)
    )
