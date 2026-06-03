package chess.persistence

import zio.*

/** A pending change to an aggregate, ready to be committed.
  *
  * The pattern: each business operation (apply-move, undo, claim-draw,
  * forfeit, lobby-create, …) builds a `Mutation` describing the new
  * value and any events the operation should publish. Cross-cutting
  * amendments (e.g. fivefold-repetition auto-draw, which needs the
  * per-session position history the service layer can't see) are
  * applied via [[amend]]. At the boundary, [[Mutation.commit]] persists
  * the final state and publishes the events — exactly once, in
  * parallel.
  *
  * Why a value instead of letting each layer call `save` itself: in
  * the previous design `GameServiceLive.makeMove` saved `newState`
  * and `GameController.makeMove` saved the (usually identical) amended
  * state, doubling the persistence cost on the hot path. The Mutation
  * value carries the intent forward — the only way to make the work
  * happen is to call `commit`, and `commit` skips the save when the
  * state hasn't actually changed from what was loaded.
  *
  * @param id         primary key of the aggregate
  * @param pre        value loaded from the store at the start of the operation
  * @param state      current value after all amendments
  * @param events     domain events to publish when the mutation commits
  */
final case class Mutation[Id, S, Ev] private (
    id: Id,
    pre: S,
    state: S,
    events: Chunk[Ev]
):
  /** Optionally amend the state, attaching an event when an amendment
    * actually happens. Returning `None` from `f` is a no-op.
    */
  def amend(f: S => Option[(S, Ev)]): Mutation[Id, S, Ev] =
    f(state).fold(this) { case (s, e) =>
      copy(state = s, events = events :+ e)
    }

  /** State-only amendment with no event. */
  def amendState(f: S => Option[S]): Mutation[Id, S, Ev] =
    f(state).fold(this)(s => copy(state = s))

  /** True iff the final state differs from what was loaded. The only
    * case a `save` is actually required — a save of `pre == state`
    * would just rewrite the same bytes.
    */
  def changed: Boolean = state != pre

object Mutation:

  /** Build a Mutation describing a single state transition.
    *
    * `pre` should be the value as it was loaded from the store; the
    * `changed` flag uses it to detect no-op writes.
    */
  def from[Id, S, Ev](id: Id, pre: S, after: S, event: Ev): Mutation[Id, S, Ev] =
    new Mutation(id, pre, after, Chunk.single(event))

  /** Build a Mutation with no events (e.g. for a no-op operation that
    * still needs to flow through the same commit path).
    */
  def unchanged[Id, S, Ev](id: Id, pre: S): Mutation[Id, S, Ev] =
    new Mutation(id, pre, pre, Chunk.empty)

  /** Persist the final state and publish all accumulated events in
    * parallel. The save is skipped entirely when `m.changed` is false;
    * an empty event chunk means `publish` is a no-op.
    *
    * Failure of either side propagates as the operation's failure.
    * Cache-style "best effort" semantics should be configured in the
    * `save` function (see `CachedGameRepository`'s parallel zipPar +
    * cache.failure-tolerance wiring).
    */
  def commit[R, E, Id, S, Ev](
      m: Mutation[Id, S, Ev],
      save: (Id, S) => ZIO[R, E, Unit],
      publish: Ev => ZIO[R, E, Unit]
  ): ZIO[R, E, Unit] =
    val saveEff    = if m.changed then save(m.id, m.state) else ZIO.unit
    val publishEff = ZIO.foreachDiscard(m.events)(publish)
    saveEff.zipPar(publishEff).unit
