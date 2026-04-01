package chess.repository

import chess.model.GameId
import chess.model.board.GameState
import zio.*

final class InMemoryGameRepository(store: Ref[Map[GameId, GameState]])
    extends GameRepository:
  def save(id: GameId, state: GameState): Task[Unit] =
    store.update(_ + (id -> state))

  def load(id: GameId): Task[Option[GameState]] =
    for s <- store.get
    yield s.get(id)

  def delete(id: GameId): Task[Unit] =
    store.update(_ - id)

object InMemoryGameRepository:
  val layer: ULayer[GameRepository] =
    ZLayer {
      Ref.make(Map.empty[GameId, GameState]).map(InMemoryGameRepository(_))
    }
