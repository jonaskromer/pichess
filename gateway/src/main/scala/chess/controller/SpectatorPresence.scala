package chess.controller

import zio.*
import zio.stream.{SubscriptionRef, ZStream}

/** Per-game spectator policy, handed to the gateway by the lobby when a
  * hosted game starts. Games with no registered policy (local / vs-bot /
  * Lichess mirror) are unrestricted. A non-positive `limit` means "no cap". */
final case class SpectatorPolicy(allowSpectate: Boolean, limit: Int)

/** Why a spectator was refused. `code` is sent verbatim to the client as
  * the data of a `spectator-denied` SSE event. */
enum SpectatorRejection(val code: String):
  case NotAllowed extends SpectatorRejection("not-allowed")
  case Full       extends SpectatorRejection("full")

/** Live spectator presence + policy per game, derived purely from open SSE
  * connections. This is a transport concern the gateway owns — game-service
  * stays oblivious to who's watching.
  *
  * Each game id gets a `SubscriptionRef[Int]`; a spectator's SSE stream
  * occupies a slot for its lifetime (via [[admit]]) and every viewer —
  * players included — subscribes to [[changes]] to render a live count.
  * [[setPolicy]] records the lobby's allowSpectate + limit so [[admit]] can
  * gate watchers.
  *
  * In-memory and unbounded over the process lifetime (one entry per game id
  * ever watched), mirroring game-service's own in-memory session map. A
  * gateway restart resets every count to zero and forgets policies, which
  * is correct: the SSE connections it was counting are gone too.
  */
final class SpectatorPresence(
    refs: Ref.Synchronized[Map[String, SubscriptionRef[Int]]],
    policies: Ref[Map[String, SpectatorPolicy]]
):

  /** Get-or-create the count ref for a game. Serialised through the
    * `Ref.Synchronized` so two concurrent first-connectors can't each
    * install a fresh ref and lose one of the increments. */
  private def refFor(gameId: String): UIO[SubscriptionRef[Int]] =
    refs.modifyZIO { map =>
      map.get(gameId) match
        case Some(ref) => ZIO.succeed((ref, map))
        case None      =>
          SubscriptionRef.make(0).map(ref => (ref, map.updated(gameId, ref)))
    }

  /** Record (or replace) the spectator policy for a game — called from the
    * lobby→gateway hand-off when a hosted game starts. */
  def setPolicy(gameId: String, allowSpectate: Boolean, limit: Int): UIO[Unit] =
    policies.update(_.updated(gameId, SpectatorPolicy(allowSpectate, limit)))

  /** Current count, then every subsequent change — the source for the
    * `spectators` SSE event delivered to every viewer of `gameId`. */
  def changes(gameId: String): ZStream[Any, Nothing, Int] =
    ZStream.unwrap(refFor(gameId).map(_.changes))

  /** Seat a spectator for the lifetime of the calling scope, honoring the
    * registered policy: refuse when spectating is disallowed, and cap
    * concurrent spectators at the limit. The check-and-seat is atomic on
    * the count ref, so two simultaneous joins can't both slip past a limit
    * of N. Games without a policy are unrestricted. On success a slot is
    * occupied and released (decremented) when the scope closes. */
  def admit(gameId: String): ZIO[Scope, SpectatorRejection, Unit] =
    policies.get.map(_.get(gameId)).flatMap {
      case Some(p) if !p.allowSpectate =>
        ZIO.fail(SpectatorRejection.NotAllowed)
      case policyOpt =>
        val limit = policyOpt.map(_.limit).getOrElse(0)
        refFor(gameId).flatMap { ref =>
          ref
            .modify(n =>
              if limit <= 0 || n < limit then (true, n + 1) else (false, n)
            )
            .flatMap {
              case true  =>
                ZIO.addFinalizer(ref.update(n => math.max(0, n - 1))).unit
              case false =>
                ZIO.fail(SpectatorRejection.Full)
            }
        }
    }

object SpectatorPresence:
  def make: UIO[SpectatorPresence] =
    for
      refs     <- Ref.Synchronized.make(Map.empty[String, SubscriptionRef[Int]])
      policies <- Ref.make(Map.empty[String, SpectatorPolicy])
    yield new SpectatorPresence(refs, policies)
