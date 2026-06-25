package chess.persistence.contract

import zio.*

import chess.persistence.GameArchiveRepository
import chess.persistence.postgres.PostgresGameArchiveRepository

object PostgresGameArchiveRepositoryContract extends GameArchiveRepositoryContract:
  override val label: String = "Postgres"
  override val repoLayer: ZLayer[Any, Throwable, GameArchiveRepository] =
    PostgresContainerLayer.databaseLayer >>> PostgresGameArchiveRepository.layer
