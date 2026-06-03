package chess.persistence.cache

import zio.*

import chess.model.{InviteCode, Lobby, LobbyError, LobbyId}
import chess.persistence.LobbyRepository

final class CachedLobbyRepository(
    cache: LobbyRepository,
    primary: LobbyRepository
) extends LobbyRepository:

  // Writes fan out to primary + cache in parallel via zipPar so the
  // caller's latency is max(primary, cache) rather than the sum.
  // Primary failures fail the operation; cache failures are logged
  // and swallowed (a missing cache entry self-heals on the next read).
  // See CachedGameRepository for the full rationale + phantom-write
  // discussion.

  def create(lobby: Lobby): IO[LobbyError, Unit] =
    primary
      .create(lobby)
      .zipPar(
        cache
          .create(lobby)
          .catchAllCause(c =>
            ZIO.logWarningCause(s"cache.create(${lobby.id}) failed — primary still authoritative", c)
          )
      )
      .unit

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
    primary
      .update(lobby)
      .zipPar(
        cache
          .update(lobby)
          .catchAllCause(c =>
            ZIO.logWarningCause(s"cache.update(${lobby.id}) failed — primary still authoritative", c)
          )
      )
      .unit

  def delete(id: LobbyId): IO[LobbyError, Unit] =
    primary
      .delete(id)
      .zipPar(
        cache
          .delete(id)
          .catchAllCause(c =>
            ZIO.logWarningCause(s"cache.delete($id) failed — primary still authoritative", c)
          )
      )
      .unit

  /** The public-lobby list isn't worth caching: it's a low-frequency,
    * always-changing aggregation. Always read straight from primary.
    */
  def listPublicWaiting(): IO[LobbyError, List[Lobby]] =
    primary.listPublicWaiting()

object CachedLobbyRepository:

  final case class Cache(repository: LobbyRepository)
  final case class Primary(repository: LobbyRepository)

  val layer: URLayer[Cache & Primary, LobbyRepository] =
    ZLayer.fromFunction { (cache: Cache, primary: Primary) =>
      new CachedLobbyRepository(cache.repository, primary.repository)
    }
