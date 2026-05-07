package chess.persistence.contract

import chess.persistence.LobbyRepository
import chess.persistence.postgres.PostgresLobbyRepository
import zio.*

object PostgresLobbyRepositoryContract extends LobbyRepositoryContract:
  override val label: String = "Postgres"
  override val repoLayer: ZLayer[Any, Throwable, LobbyRepository] =
    PostgresContainerLayer.databaseLayer >>> PostgresLobbyRepository.layer
