package chess.gameservice

import chess.model.{GameError, GameId, GameSnapshot, SessionState}
import zio.*
import zio.stream.SubscriptionRef

/** Holds one `SubscriptionRef[SessionState]` per active game.
  *
  * Replaces the single shared `session` previously held in `app/Main`. Each
  * gRPC client connects to the same in-memory map; concurrent requests on
  * the same game are serialised by `SubscriptionRef.modifyZIO`'s internal
  * semaphore (same atomicity guarantee as the old in-process model).
  *
  * State is in-memory only — gameService restart drops every active game.
  * Replay-from-Kafka-on-startup is the documented next iteration.
  */
final class GameSessions(
    sessions: Ref.Synchronized[Map[GameId, SubscriptionRef[SessionState]]]
):
  def register(snapshot: GameSnapshot): UIO[SubscriptionRef[SessionState]] =
    sessions.modifyZIO { map =>
      SubscriptionRef.make(SessionState(snapshot)).map(ref => (ref, map + (snapshot.gameId -> ref)))
    }

  def get(id: GameId): IO[GameError, SubscriptionRef[SessionState]] =
    sessions.get.flatMap { map =>
      ZIO
        .fromOption(map.get(id))
        .orElseFail(GameError.GameNotFound(id))
    }

object GameSessions:
  val layer: ULayer[GameSessions] =
    ZLayer.fromZIO(
      Ref.Synchronized
        .make(Map.empty[GameId, SubscriptionRef[SessionState]])
        .map(new GameSessions(_))
    )
