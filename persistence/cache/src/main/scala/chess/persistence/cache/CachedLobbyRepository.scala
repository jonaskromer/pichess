package chess.persistence.cache

import chess.model.{InviteCode, Lobby, LobbyError, LobbyId}
import chess.persistence.LobbyRepository
import zio.*

final class CachedLobbyRepository(
    cache: LobbyRepository,
    primary: LobbyRepository
) extends LobbyRepository:

  def create(lobby: Lobby): IO[LobbyError, Unit] =
    primary.create(lobby) *> cache.create(lobby)

  def findById(id: LobbyId): IO[LobbyError, Option[Lobby]] =
    cache.findById(id).flatMap {
      case some @ Some(_) => ZIO.succeed(some)
      case None =>
        primary.findById(id).tap {
          case Some(l) => cache.create(l)
          case None    => ZIO.unit
        }
    }

  def findByInviteCode(code: InviteCode): IO[LobbyError, Option[Lobby]] =
    cache.findByInviteCode(code).flatMap {
      case some @ Some(_) => ZIO.succeed(some)
      case None =>
        primary.findByInviteCode(code).tap {
          case Some(l) => cache.create(l)
          case None    => ZIO.unit
        }
    }

  def update(lobby: Lobby): IO[LobbyError, Unit] =
    primary.update(lobby) *> cache.update(lobby)

  def delete(id: LobbyId): IO[LobbyError, Unit] =
    primary.delete(id) *> cache.delete(id)

object CachedLobbyRepository:

  final case class Cache(repository: LobbyRepository)
  final case class Primary(repository: LobbyRepository)

  val layer: URLayer[Cache & Primary, LobbyRepository] =
    ZLayer.fromFunction { (cache: Cache, primary: Primary) =>
      new CachedLobbyRepository(cache.repository, primary.repository)
    }
