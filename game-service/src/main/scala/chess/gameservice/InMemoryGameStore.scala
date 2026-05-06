package chess.gameservice

import chess.model.{GameError, GameId}
import chess.model.board.GameState
import zio.*

/** In-memory map of `gameId` → current `GameState`, owned by `gameService`.
  *
  * Replaces the old `GameRepository` dependency: after the strangler step,
  * gameService is the authoritative holder of in-flight game state and
  * publishes events to Kafka. The repository service is fed exclusively by
  * the `chess.game-events` topic and is never called synchronously from
  * gameService.
  *
  * Plain `Ref[Map]`. Restart loses state — accepted for now; replay-from-Kafka
  * on startup is a future iteration.
  */
trait GameStore:
  def save(id: GameId, state: GameState): IO[GameError, Unit]
  def load(id: GameId): IO[GameError, Option[GameState]]

object GameStore:
  def save(id: GameId, state: GameState): ZIO[GameStore, GameError, Unit] =
    ZIO.serviceWithZIO[GameStore](_.save(id, state))
  def load(id: GameId): ZIO[GameStore, GameError, Option[GameState]] =
    ZIO.serviceWithZIO[GameStore](_.load(id))

final class InMemoryGameStore(ref: Ref[Map[GameId, GameState]])
    extends GameStore:
  def save(id: GameId, state: GameState): IO[GameError, Unit] =
    ref.update(_ + (id -> state))
  def load(id: GameId): IO[GameError, Option[GameState]] =
    ref.get.map(_.get(id))

object InMemoryGameStore:
  val layer: ULayer[GameStore] =
    ZLayer.fromZIO(
      Ref.make(Map.empty[GameId, GameState]).map(new InMemoryGameStore(_))
    )
