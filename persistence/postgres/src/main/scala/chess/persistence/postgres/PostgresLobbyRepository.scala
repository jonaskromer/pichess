package chess.persistence.postgres

import chess.model.{
  InviteCode,
  Lobby,
  LobbyError,
  LobbyId,
  LobbyStatus,
  LobbyVisibility
}
import chess.persistence.LobbyRepository
import slick.jdbc.PostgresProfile.api.*
import zio.*

import java.time.Instant

final class PostgresLobbyRepository(db: PostgresDatabase) extends LobbyRepository:

  def create(lobby: Lobby): IO[LobbyError, Unit] =
    db.run(Tables.lobbies += toRow(lobby)).unit.mapError(toInfraError)

  def findById(id: LobbyId): IO[LobbyError, Option[Lobby]] =
    db.run(Tables.lobbies.filter(_.id === id).result.headOption)
      .mapError(toInfraError)
      .map(_.map(fromRow))

  def findByInviteCode(code: InviteCode): IO[LobbyError, Option[Lobby]] =
    db.run(
      Tables.lobbies.filter(_.inviteCode === code.value).result.headOption
    ).mapError(toInfraError)
      .map(_.map(fromRow))

  def update(lobby: Lobby): IO[LobbyError, Unit] =
    db.run(Tables.lobbies.insertOrUpdate(toRow(lobby))).unit
      .mapError(toInfraError)

  def delete(id: LobbyId): IO[LobbyError, Unit] =
    db.run(Tables.lobbies.filter(_.id === id).delete).unit
      .mapError(toInfraError)

  def listPublicWaiting(): IO[LobbyError, List[Lobby]] =
    db.run(
      Tables.lobbies
        .filter(l =>
          l.visibility === LobbyVisibility.Public.toString &&
            l.status === LobbyStatus.Waiting.toString
        )
        .sortBy(_.createdAt.asc)
        .result
    ).mapError(toInfraError)
      .map(_.toList.map(fromRow))

  private def toRow(lobby: Lobby): Tables.LobbyRow =
    Tables.LobbyRow(
      id = lobby.id,
      inviteCode = lobby.inviteCode.value,
      hostNickname = lobby.hostNickname,
      hostSessionId = lobby.hostSessionId,
      guestNickname = lobby.guestNickname,
      guestSessionId = lobby.guestSessionId,
      visibility = lobby.visibility.toString,
      allowUndo = lobby.allowUndo,
      allowSpectate = lobby.allowSpectate,
      spectatorLimit = lobby.spectatorLimit,
      status = lobby.status.toString,
      gameId = lobby.gameId,
      createdAt = lobby.createdAt,
      updatedAt = Instant.now
    )

  private def fromRow(row: Tables.LobbyRow): Lobby =
    Lobby(
      id = row.id,
      inviteCode = InviteCode.unsafe(row.inviteCode),
      hostNickname = row.hostNickname,
      hostSessionId = row.hostSessionId,
      guestNickname = row.guestNickname,
      guestSessionId = row.guestSessionId,
      visibility = parseVisibility(row.visibility),
      allowUndo = row.allowUndo,
      allowSpectate = row.allowSpectate,
      spectatorLimit = row.spectatorLimit,
      status = parseStatus(row.status),
      createdAt = row.createdAt,
      gameId = row.gameId
    )

  private def parseStatus(raw: String): LobbyStatus =
    LobbyStatus.values
      .find(_.toString == raw)
      .getOrElse(LobbyStatus.Closed)

  private def parseVisibility(raw: String): LobbyVisibility =
    LobbyVisibility.values
      .find(_.toString == raw)
      .getOrElse(LobbyVisibility.Private)

  private def toInfraError(t: Throwable): LobbyError =
    LobbyError.InfrastructureError(s"Postgres error: ${t.getMessage}")

object PostgresLobbyRepository:
  val layer: URLayer[PostgresDatabase, LobbyRepository] =
    ZLayer.fromFunction(PostgresLobbyRepository(_))
