package chess.repository

import chess.model.{GameError, GameId}
import chess.model.board.GameState
import zio.*

trait GameRepository:
  def save(id: GameId, state: GameState): IO[GameError, Unit]
  def load(id: GameId): IO[GameError, Option[GameState]]
  def delete(id: GameId): IO[GameError, Unit]

object GameRepository:
  def save(id: GameId, state: GameState): ZIO[GameRepository, GameError, Unit] =
    ZIO.serviceWithZIO[GameRepository](_.save(id, state))

  def load(
      id: GameId
  ): ZIO[GameRepository, GameError, Option[GameState]] =
    ZIO.serviceWithZIO[GameRepository](_.load(id))

  def delete(id: GameId): ZIO[GameRepository, GameError, Unit] =
    ZIO.serviceWithZIO[GameRepository](_.delete(id))
