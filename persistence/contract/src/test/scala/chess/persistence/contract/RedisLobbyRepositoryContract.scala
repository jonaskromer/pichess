package chess.persistence.contract

import zio.*

import chess.persistence.LobbyRepository
import chess.persistence.redis.RedisLobbyRepository

object RedisLobbyRepositoryContract extends LobbyRepositoryContract:
  override val label: String = "Redis"
  override val repoLayer: ZLayer[Any, Throwable, LobbyRepository] =
    RedisContainerLayer.redisLayer >>> RedisLobbyRepository.layer
