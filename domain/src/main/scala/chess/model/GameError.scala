package chess.model

/** Domain error type used throughout the chess engine + services.
  *
  * Extends `Exception` so it composes cleanly with ZIO's error
  * channel (`IO[GameError, _]`) and Java interop layers, but
  * '''stack trace capture is suppressed''' (override of
  * `fillInStackTrace`). Rationale:
  *
  *   1. The hot search loop calls `Game.applyMoveCore` for every
  *      candidate move from `MoveValidator.legalMovesFrom` to test
  *      legality via `catchAll`. Profiling showed
  *      `GameError.<init>` + `Throwable.fillInStackTrace` taking
  *      ~70% of total CPU at depth 4 — the cost is dominated by
  *      stack-trace materialisation for exceptions that are
  *      caught + discarded one frame up.
  *   2. No callers (production or tests) call `getStackTrace` /
  *      `printStackTrace` on a `GameError`. The `message` field
  *      already carries human-readable context, and Logging /
  *      gRPC error mappers project from the case (`InvalidMove`,
  *      `ParseError`, …) and message, not the stack.
  *   3. Constructing a Throwable without a stack trace is a
  *      well-established Java perf pattern for domain errors that
  *      double as exceptions.
  *
  * If a specific code path ever needs a real stack trace (e.g.
  * debugging an `InfrastructureError` in production), it can be
  * re-enabled per-construction by overriding `fillInStackTrace`
  * in a wrapper, or via `Thread.currentThread.getStackTrace` at
  * the construction site.
  */
enum GameError(val message: String) extends Exception(message):
  case ParseError(msg: String) extends GameError(msg)
  case InvalidMove(msg: String) extends GameError(msg)
  case GameNotFound(id: GameId) extends GameError(s"Game not found: $id")

  /** Transport / infrastructure failure talking to a downstream service — e.g.
    * the repository microservice is unreachable or returned 5xx. Kept distinct
    * from [[ParseError]] so retry policies can target it.
    */
  case InfrastructureError(msg: String) extends GameError(msg)

  // ── Stack-trace suppression ─────────────────────────────────────
  // Override the JDK's expensive native stack-walk; the search
  // loop creates these by the thousand and we don't read traces.
  override def fillInStackTrace(): Throwable = this
