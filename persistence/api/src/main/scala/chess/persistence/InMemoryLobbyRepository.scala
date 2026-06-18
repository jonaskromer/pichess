package chess.persistence

import zio.*

import chess.model.{InviteCode, Lobby, LobbyError, LobbyId, LobbyStatus, LobbyVisibility}

final class InMemoryLobbyRepository(store: Ref[Map[LobbyId, Lobby]])
    extends LobbyRepository:

  def create(lobby: Lobby): IO[LobbyError, Unit] =
    store.update(_ + (lobby.id -> lobby))

  def findById(id: LobbyId): IO[LobbyError, Option[Lobby]] =
    store.get.map(_.get(id))

  def findByInviteCode(code: InviteCode): IO[LobbyError, Option[Lobby]] =
    store.get.map(_.values.find(_.inviteCode == code))

  def update(lobby: Lobby): IO[LobbyError, Unit] =
    store.update(_ + (lobby.id -> lobby))

  def delete(id: LobbyId): IO[LobbyError, Unit] =
    store.update(_ - id)

  def listPublicActive(): IO[LobbyError, List[Lobby]] =
    store.get.map(
      _.values
        .filter(l =>
          l.visibility == LobbyVisibility.Public &&
            l.status != LobbyStatus.Closed
        )
        .toList
        .sortBy(_.createdAt)
    )

object InMemoryLobbyRepository:
  val layer: ULayer[LobbyRepository] =
    ZLayer {
      Ref.make(Map.empty[LobbyId, Lobby]).map(InMemoryLobbyRepository(_))
    }
