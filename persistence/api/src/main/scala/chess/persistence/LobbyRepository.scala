package chess.persistence

import chess.model.{InviteCode, Lobby, LobbyError, LobbyId}
import zio.*

/** Backend-agnostic CRUD on Lobby aggregates.
  *
  * Implementations live in sibling modules. `findByInviteCode` is the hottest
  * read path (every join goes through it) — backends should index on it.
  *
  * `listPublicWaiting` powers the web-ui's public-lobby browser; backends
  * are free to filter at the storage layer (SQL `WHERE`, Mongo query,
  * Cassandra denormalised table, etc.) but must return only Lobbies whose
  * status is `Waiting` and visibility is `Public`.
  */
trait LobbyRepository:
  def create(lobby: Lobby): IO[LobbyError, Unit]
  def findById(id: LobbyId): IO[LobbyError, Option[Lobby]]
  def findByInviteCode(code: InviteCode): IO[LobbyError, Option[Lobby]]
  def update(lobby: Lobby): IO[LobbyError, Unit]
  def delete(id: LobbyId): IO[LobbyError, Unit]
  def listPublicWaiting(): IO[LobbyError, List[Lobby]]

object LobbyRepository:
  def create(lobby: Lobby): ZIO[LobbyRepository, LobbyError, Unit] =
    ZIO.serviceWithZIO[LobbyRepository](_.create(lobby))

  def findById(
      id: LobbyId
  ): ZIO[LobbyRepository, LobbyError, Option[Lobby]] =
    ZIO.serviceWithZIO[LobbyRepository](_.findById(id))

  def findByInviteCode(
      code: InviteCode
  ): ZIO[LobbyRepository, LobbyError, Option[Lobby]] =
    ZIO.serviceWithZIO[LobbyRepository](_.findByInviteCode(code))

  def update(lobby: Lobby): ZIO[LobbyRepository, LobbyError, Unit] =
    ZIO.serviceWithZIO[LobbyRepository](_.update(lobby))

  def delete(id: LobbyId): ZIO[LobbyRepository, LobbyError, Unit] =
    ZIO.serviceWithZIO[LobbyRepository](_.delete(id))

  def listPublicWaiting(): ZIO[LobbyRepository, LobbyError, List[Lobby]] =
    ZIO.serviceWithZIO[LobbyRepository](_.listPublicWaiting())
