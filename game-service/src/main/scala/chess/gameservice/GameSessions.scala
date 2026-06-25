package chess.gameservice

import zio.*
import zio.stream.SubscriptionRef

import chess.model.{ClockState, GameError, GameId, GameSnapshot, SessionState}

/** Holds one `SubscriptionRef[SessionState]` per active game.
  *
  * Replaces the single shared `session` previously held in `app/Main`. Each
  * gRPC client connects to the same in-memory map; concurrent requests on the
  * same game are serialised by `SubscriptionRef.modifyZIO`'s internal semaphore
  * (same atomicity guarantee as the old in-process model).
  *
  * State is in-memory only — gameService restart drops every active game.
  * Replay-from-Kafka-on-startup is the documented next iteration.
  */
final class GameSessions(
    sessions: Ref.Synchronized[Map[GameId, SubscriptionRef[SessionState]]]
):
  def register(
      snapshot: GameSnapshot,
      clock: Option[ClockState] = None
  ): UIO[SubscriptionRef[SessionState]] =
    sessions.modifyZIO { map =>
      SubscriptionRef
        .make(SessionState(snapshot, clock = clock))
        .map(ref => (ref, map + (snapshot.gameId -> ref)))
    }

  def get(id: GameId): IO[GameError, SubscriptionRef[SessionState]] =
    sessions.get.flatMap { map =>
      ZIO
        .fromOption(map.get(id))
        .orElseFail(GameError.GameNotFound(id))
    }

  /** Snapshot every live session as `(gameId, current SessionState)`. Finished
    * games linger in the map until eviction, so callers filter by status (e.g.
    * the spectate index keeps only still-playing games).
    */
  def all: UIO[List[(GameId, SessionState)]] =
    sessions.get.flatMap { map =>
      ZIO.foreach(map.toList) { case (id, ref) => ref.get.map(id -> _) }
    }

object GameSessions:
  val layer: ULayer[GameSessions] =
    ZLayer.fromZIO(
      Ref.Synchronized
        .make(Map.empty[GameId, SubscriptionRef[SessionState]])
        .map(new GameSessions(_))
    )
