package chess.persistence.postgres

import chess.model.{InviteCode, Lobby, LobbyError, LobbyId, LobbyStatus}
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

  private def toRow(lobby: Lobby): Tables.LobbyRow =
    Tables.LobbyRow(
      id = lobby.id,
      inviteCode = lobby.inviteCode.value,
      hostNickname = lobby.hostNickname,
      guestNickname = lobby.guestNickname,
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
      guestNickname = row.guestNickname,
      status = parseStatus(row.status),
      createdAt = row.createdAt,
      gameId = row.gameId
    )

  private def parseStatus(raw: String): LobbyStatus =
    LobbyStatus.values
      .find(_.toString == raw)
      .getOrElse(LobbyStatus.Closed)

  private def toInfraError(t: Throwable): LobbyError =
    LobbyError.InfrastructureError(s"Postgres error: ${t.getMessage}")

object PostgresLobbyRepository:
  val layer: URLayer[PostgresDatabase, LobbyRepository] =
    ZLayer.fromFunction(PostgresLobbyRepository(_))
