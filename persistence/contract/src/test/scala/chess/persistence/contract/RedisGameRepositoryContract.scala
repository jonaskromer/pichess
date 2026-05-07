package chess.persistence.contract

import chess.persistence.GameRepository
import chess.persistence.redis.RedisGameRepository
import zio.*

object RedisGameRepositoryContract extends GameRepositoryContract:
  override val label: String = "Redis"
  override val repoLayer: ZLayer[Any, Throwable, GameRepository] =
    RedisContainerLayer.redisLayer >>> RedisGameRepository.layer
