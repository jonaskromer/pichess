package chess.persistence

import zio.*

import chess.model.board.GameState
import chess.model.{GameError, GameId}

final class InMemoryGameRepository(store: Ref[Map[GameId, GameState]])
    extends GameRepository:
  def save(id: GameId, state: GameState): IO[GameError, Unit] =
    store.update(_ + (id -> state))

  def load(id: GameId): IO[GameError, Option[GameState]] =
    store.get.map(_.get(id))

  def delete(id: GameId): IO[GameError, Unit] =
    store.update(_ - id)

object InMemoryGameRepository:
  val layer: ULayer[GameRepository] =
    ZLayer {
      Ref.make(Map.empty[GameId, GameState]).map(InMemoryGameRepository(_))
    }
