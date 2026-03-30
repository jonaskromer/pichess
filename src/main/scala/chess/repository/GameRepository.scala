package chess.repository

import chess.model.{GameId}
import chess.model.board.GameState
import zio.*

trait GameRepository:
  def save(id: GameId, state: GameState): Task[Unit]
  def load(id: GameId): Task[Option[GameState]]
  def delete(id: GameId): Task[Unit]

object GameRepository:
  def save(id: GameId, state: GameState): ZIO[GameRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[GameRepository](_.save(id, state))

  def load(id: GameId): ZIO[GameRepository, Throwable, Option[GameState]] =
    ZIO.serviceWithZIO[GameRepository](_.load(id))

  def delete(id: GameId): ZIO[GameRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[GameRepository](_.delete(id))
