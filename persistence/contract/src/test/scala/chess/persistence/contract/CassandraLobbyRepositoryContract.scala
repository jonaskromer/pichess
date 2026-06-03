package chess.persistence.contract

import zio.*

import chess.persistence.LobbyRepository
import chess.persistence.cassandra.CassandraLobbyRepository

object CassandraLobbyRepositoryContract extends LobbyRepositoryContract:
  override val label: String = "Cassandra"
  override val repoLayer: ZLayer[Any, Throwable, LobbyRepository] =
    CassandraContainerLayer.sessionLayer >>> CassandraLobbyRepository.layer
