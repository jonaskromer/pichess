package chess.persistence.contract

import zio.*

import chess.persistence.GameArchiveRepository
import chess.persistence.mongo.MongoGameArchiveRepository

object MongoGameArchiveRepositoryContract extends GameArchiveRepositoryContract:
  override val label: String = "Mongo"
  override val repoLayer: ZLayer[Any, Throwable, GameArchiveRepository] =
    MongoContainerLayer.databaseLayer >>> MongoGameArchiveRepository.layer
