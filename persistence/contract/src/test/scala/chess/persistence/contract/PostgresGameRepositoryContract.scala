package chess.persistence.contract

import chess.persistence.GameRepository
import chess.persistence.postgres.PostgresGameRepository
import zio.*

object PostgresGameRepositoryContract extends GameRepositoryContract:
  override val label: String = "Postgres"
  override val repoLayer: ZLayer[Any, Throwable, GameRepository] =
    PostgresContainerLayer.databaseLayer >>> PostgresGameRepository.layer
