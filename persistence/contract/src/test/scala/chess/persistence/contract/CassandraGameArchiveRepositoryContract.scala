package chess.persistence.contract

import zio.*

import chess.persistence.GameArchiveRepository
import chess.persistence.cassandra.CassandraGameArchiveRepository

object CassandraGameArchiveRepositoryContract extends GameArchiveRepositoryContract:
  override val label: String = "Cassandra"
  override val repoLayer: ZLayer[Any, Throwable, GameArchiveRepository] =
    CassandraContainerLayer.sessionLayer >>> CassandraGameArchiveRepository.layer
