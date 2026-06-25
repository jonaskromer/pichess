package chess.model

import chess.model.piece.Color

/** The authoritative chess clock for a timed game (the game-service is the
  * source of truth).
  *
  *   - `whiteMs` / `blackMs` are the **banked** remaining milliseconds — exact
  *     as of the moment the side-to-move's clock last started running (i.e. the
  *     last move, or game start). The running side's *live* remaining is
  *     `banked − (now − runningSince)`; the browser interpolates that locally.
  *   - `incrementMs` is the Fischer increment banked after each move.
  *   - `runningSince` is the epoch-ms at which the side-to-move's clock started
  *     counting, or `None` when the clock is paused (game over / not started).
  *
  * All transitions are pure and take an explicit `now` so they are
  * deterministic under test; the wall-clock read happens at the call site
  * (`GameController` / the timeout daemon).
  */
final case class ClockState(
    whiteMs: Long,
    blackMs: Long,
    incrementMs: Long,
    runningSince: Option[Long]
):
  def bankedFor(color: Color): Long =
    if color == Color.White then whiteMs else blackMs

  private def withBanked(color: Color, ms: Long): ClockState =
    if color == Color.White then copy(whiteMs = ms) else copy(blackMs = ms)

  /** Live remaining ms for `color` at `now`. The running side ticks down (never
    * below zero); a non-running side reads its banked value.
    */
  def liveRemaining(color: Color, sideToMove: Color, now: Long): Long =
    runningSince match
      case Some(since) if color == sideToMove =>
        (bankedFor(color) - (now - since)).max(0L)
      case _ => bankedFor(color)

  /** Apply a completed move by `mover` at `now`: bank the elapsed time off the
    * mover's clock, add the Fischer increment, and start the opponent's clock.
    */
  def afterMove(mover: Color, now: Long): ClockState =
    val elapsed = runningSince.map(now - _).getOrElse(0L)
    val banked = (bankedFor(mover) - elapsed + incrementMs).max(0L)
    withBanked(mover, banked).copy(runningSince = Some(now))

  /** Freeze the clock (the game just ended), banking the running side's elapsed
    * time first so the final remaining is accurate. Idempotent once paused.
    */
  def stopped(sideToMove: Color, now: Long): ClockState =
    runningSince match
      case None => this
      case Some(_) =>
        withBanked(sideToMove, liveRemaining(sideToMove, sideToMove, now))
          .copy(runningSince = None)

  /** Whether `sideToMove`'s clock has run out as of `now`. Only a running clock
    * can flag.
    */
  def flagged(sideToMove: Color, now: Long): Boolean =
    runningSince.exists(since => bankedFor(sideToMove) - (now - since) <= 0L)

object ClockState:
  /** A fresh clock for a timed game: both sides at `initialMs`, the
    * side-to-move's clock running from `now` (White moves first).
    */
  def initial(initialMs: Long, incrementMs: Long, now: Long): ClockState =
    ClockState(initialMs, initialMs, incrementMs, Some(now))
