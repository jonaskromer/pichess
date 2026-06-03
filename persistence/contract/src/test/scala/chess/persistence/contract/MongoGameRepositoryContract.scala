package chess.persistence.contract

import zio.*

import chess.persistence.GameRepository
import chess.persistence.mongo.MongoGameRepository

object MongoGameRepositoryContract extends GameRepositoryContract:
  override val label: String = "Mongo"
  override val repoLayer: ZLayer[Any, Throwable, GameRepository] =
    MongoContainerLayer.databaseLayer >>> MongoGameRepository.layer
