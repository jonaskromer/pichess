package chess.bot.engine

import java.util.concurrent.Semaphore

/** A process-global cap on concurrent **search-helper** threads, shared across
  * every per-game [[Search]] so they don't oversubscribe the CPU when the bot
  * plays several games at once.
  *
  * The contract is deliberately asymmetric:
  *   - A search's **main worker always runs** — it never touches the budget,
  *     so an essential move is never delayed behind speculative work (no flag
  *     risk).
  *   - **LazySMP helpers** grab spare permits **non-blocking** ([[acquireHelpers]]):
  *     they only spawn when cores are otherwise idle.
  *
  * So: one active game → it grabs all spare cores (full multi-core LazySMP);
  * N active games → the first few exhaust the budget and the rest run
  * main-only (single-thread + incremental). Adaptive, bounded, never blocks. */
final class ParallelismBudget(val permits: Int):

  private val sem = new Semaphore(math.max(0, permits))

  /** Grab up to `maxHelpers` spare permits without blocking — returns how many
    * were actually acquired (`0..maxHelpers`). The caller's main worker runs
    * regardless. Always pair the returned count with [[release]]. */
  def acquireHelpers(maxHelpers: Int): Int =
    var n = 0
    while n < maxHelpers && sem.tryAcquire() do n += 1
    n

  /** Return `n` previously-acquired helper permits. */
  def release(n: Int): Unit = if n > 0 then sem.release(n)

  /** Currently-free permits (diagnostics / tests). */
  def available: Int = sem.availablePermits()

object ParallelismBudget:

  /** No helpers — every search runs single-threaded (the default; LazySMP off). */
  val Single: ParallelismBudget = new ParallelismBudget(0)

  /** A budget sized to the machine, reserving `reserve` cores for the main
    * workers + I/O. So total search threads ≈ availableProcessors. */
  def ofCores(reserve: Int = 1): ParallelismBudget =
    new ParallelismBudget(math.max(0, Runtime.getRuntime.availableProcessors() - reserve))
