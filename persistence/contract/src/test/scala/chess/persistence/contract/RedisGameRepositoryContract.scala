package chess.persistence.contract

import zio.*

import chess.persistence.GameRepository
import chess.persistence.redis.RedisGameRepository

object RedisGameRepositoryContract extends GameRepositoryContract:
  override val label: String = "Redis"
  override val repoLayer: ZLayer[Any, Throwable, GameRepository] =
    RedisContainerLayer.redisLayer >>> RedisGameRepository.layer
