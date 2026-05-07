package chess.persistence.contract

import chess.persistence.LobbyRepository
import chess.persistence.mongo.MongoLobbyRepository
import zio.*

object MongoLobbyRepositoryContract extends LobbyRepositoryContract:
  override val label: String = "Mongo"
  override val repoLayer: ZLayer[Any, Throwable, LobbyRepository] =
    MongoContainerLayer.databaseLayer >>> MongoLobbyRepository.withIndexesLayer
