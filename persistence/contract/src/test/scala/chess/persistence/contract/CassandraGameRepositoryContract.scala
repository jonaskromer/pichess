package chess.persistence.contract

import zio.*

import chess.persistence.GameRepository
import chess.persistence.cassandra.CassandraGameRepository

object CassandraGameRepositoryContract extends GameRepositoryContract:
  override val label: String = "Cassandra"
  override val repoLayer: ZLayer[Any, Throwable, GameRepository] =
    CassandraContainerLayer.sessionLayer >>> CassandraGameRepository.layer
