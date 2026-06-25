package chess.persistence.contract

import zio.*

import chess.persistence.GameArchiveRepository
import chess.persistence.redis.RedisGameArchiveRepository

object RedisGameArchiveRepositoryContract extends GameArchiveRepositoryContract:
  override val label: String = "Redis"
  override val repoLayer: ZLayer[Any, Throwable, GameArchiveRepository] =
    RedisContainerLayer.redisLayer >>> RedisGameArchiveRepository.layer
