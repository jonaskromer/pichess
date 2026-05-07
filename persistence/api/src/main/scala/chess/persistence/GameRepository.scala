package chess.persistence

import chess.model.{GameError, GameId}
import chess.model.board.GameState
import zio.*

/** Backend-agnostic CRUD on game state, keyed by `GameId`.
  *
  * Implementations live in sibling modules (persistence-postgres, persistence-
  * mongo, persistence-redis, persistence-cassandra) plus the in-memory dev
  * default in this module. All implementations must satisfy the contract
  * spec in persistence-contract — anything that doesn't is not actually a
  * drop-in.
  *
  * Returns `IO[GameError, A]` only; driver-specific types (DBIO, Future,
  * BsonDocument, …) must not leak through this trait.
  */
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
