package chess.bot.tournament

import zio.*

import chess.bot.engine.Search

/** Plays MULTIPLE tournaments concurrently as one bot identity.
  *
  * Registers piChess once (shared token, idempotent), then `join`s any number
  * of tournaments — each runs in its own `forkDaemon` fiber with
  * reconnect-retry, deduped by id. The shared engine (`searchFactory` + the
  * loaded net + the global LazySMP budget) is reused across every tournament;
  * the per-tournament and per-game logic is [[TournamentBridge]]'s, unchanged.
  *
  * This is the seam the Phase-2 control API drives: `join` (signalled by the
  * gateway), `leave`, and `activeTournaments` for status.
  */
final class TournamentManager private (
    botName: String,
    fallbackDepth: Int,
    searchFactory: () => Search,
    api: TournamentApiClient,
    reconnectDelay: Duration,
    recorder: Option[GameRecorder],
    metadata: Option[BotMetadata],
    registered: Ref.Synchronized[Option[String]],
    active: Ref.Synchronized[Map[String, Fiber.Runtime[Throwable, Unit]]]
):

  /** Register piChess exactly once and return our bot id. Concurrent callers
    * serialise on the `Ref.Synchronized`, so `register` is hit at most once.
    * After the auth registration we also refresh our bot-registry entry with
    * engine metadata (best-effort — a failure must never block play).
    */
  def ensureRegistered: IO[Throwable, String] =
    registered.modifyZIO {
      case s @ Some(id) => ZIO.succeed((id, s))
      case None =>
        api.register(botName).flatMap { reg =>
          ZIO
            .foreachDiscard(metadata)(meta =>
              api
                .registerInRegistry(botName, meta)
                .catchAll(err =>
                  ZIO.logWarning(
                    s"bot-registry metadata registration failed (non-fatal): ${err.getMessage}"
                  )
                )
            ) *>
            ZIO
              .logInfo(
                s"Registered with tournament server as ${reg.id} (name='$botName')"
              )
              .as((reg.id, Some(reg.id)))
        }
    }

  /** Join a tournament and start playing it. Idempotent: a second join of the
    * same id while it's still active is a no-op. Returns once the player fiber
    * is forked (the games then play in the background).
    */
  def join(tournamentId: String): IO[Throwable, Unit] =
    ensureRegistered.flatMap { myId =>
      active
        .modifyZIO { current =>
          if current.contains(tournamentId) then ZIO.succeed((false, current))
          else
            supervised(tournamentId, myId).forkDaemon
              .map(fiber => (true, current.updated(tournamentId, fiber)))
        }
        .flatMap { started =>
          ZIO.when(started)(ZIO.logInfo(s"Joined tournament $tournamentId")) *>
            publishActiveCount
        }
    }

  /** Mirror the active-tournament count into the Grafana gauge. Called from
    * every mutation of `active` (join, leave, and the supervised auto-drop).
    */
  private def publishActiveCount: UIO[Unit] =
    active.get.flatMap(m => TournamentMetrics.activeTournaments(m.size))

  /** Stop following a tournament: interrupt its fiber and drop it. No-op if not
    * active. The fiber is pulled out under the lock (pure) and interrupted
    * *outside* it, so the fiber's own cleanup (which also touches `active`)
    * can't deadlock against this call.
    */
  def leave(tournamentId: String): UIO[Unit] =
    active
      .modifyZIO(m => ZIO.succeed((m.get(tournamentId), m - tournamentId)))
      .flatMap {
        case Some(fiber) =>
          fiber.interrupt *> ZIO.logInfo(s"Left tournament $tournamentId")
        case None => ZIO.unit
      } *> publishActiveCount

  /** The tournaments currently being played. */
  def activeTournaments: UIO[Set[String]] = active.get.map(_.keySet)

  /** One supervised tournament: play it, reconnecting on stream failure, and
    * drop it from `active` when it finally ends (so a finished tournament can
    * be re-joined and status stays accurate).
    */
  private[tournament] def supervised(
      tournamentId: String,
      myId: String
  ): IO[Throwable, Unit] =
    TournamentBridge
      .playTournament(tournamentId, myId, fallbackDepth, searchFactory, api, recorder)
      .tapErrorCause(c =>
        ZIO.logErrorCause(
          s"Tournament $tournamentId stream failed; reconnecting",
          c
        )
      )
      .retry(Schedule.fixed(reconnectDelay))
      .ensuring(active.update(_ - tournamentId) *> publishActiveCount)

object TournamentManager:

  /** Build a manager. `reconnectDelay` is the per-tournament stream-reconnect
    * backoff (tests pass `Duration.Zero`).
    */
  def make(
      botName: String,
      fallbackDepth: Int,
      searchFactory: () => Search,
      api: TournamentApiClient,
      reconnectDelay: Duration = 5.seconds,
      recorder: Option[GameRecorder] = None,
      metadata: Option[BotMetadata] = None
  ): UIO[TournamentManager] =
    for
      registered <- Ref.Synchronized.make(Option.empty[String])
      active <- Ref.Synchronized.make(
        Map.empty[String, Fiber.Runtime[Throwable, Unit]]
      )
    yield new TournamentManager(
      botName,
      fallbackDepth,
      searchFactory,
      api,
      reconnectDelay,
      recorder,
      metadata,
      registered,
      active
    )
