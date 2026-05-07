package chess.persistence.runtime

import chess.persistence.{
  Backend,
  BackendConfig,
  CacheBackend,
  GameRepository,
  InMemoryGameRepository,
  InMemoryLobbyRepository,
  LobbyRepository
}
import chess.persistence.cache.{CachedGameRepository, CachedLobbyRepository}
import chess.persistence.cassandra.{
  CassandraGameRepository,
  CassandraLobbyRepository,
  CassandraSession
}
import chess.persistence.mongo.{
  MongoClientLayer,
  MongoGameRepository,
  MongoLobbyRepository
}
import chess.persistence.postgres.{
  PostgresDatabase,
  PostgresGameRepository,
  PostgresLobbyRepository
}
import chess.persistence.redis.{
  RedisGameRepository,
  RedisLayers,
  RedisLobbyRepository
}
import zio.*

/** Maps a [[BackendConfig]] to the right `GameRepository` /
  * `LobbyRepository` layer, including the optional Redis-cache decorator.
  *
  * Centralised here so the three service Mains (game-service, repository,
  * lobby-service) share one switch instead of duplicating five-case matches.
  * Adding a new backend or wiring up a new decorator is a single-file
  * change.
  */
object PersistenceLayers:

  // -- Game repository -------------------------------------------------------

  def gameRepository(cfg: BackendConfig): TaskLayer[GameRepository] =
    cfg.cache match
      case CacheBackend.NoCache => primaryGameRepo(cfg.backend)
      case CacheBackend.Redis   => cachedGameRepo(cfg.backend)

  private def primaryGameRepo(backend: Backend): TaskLayer[GameRepository] =
    backend match
      case Backend.InMemory  => InMemoryGameRepository.layer
      case Backend.Postgres  =>
        PostgresDatabase.withSchemaLayer >>> PostgresGameRepository.layer
      case Backend.Mongo     =>
        MongoClientLayer.layer >>> MongoGameRepository.layer
      case Backend.Redis     =>
        RedisLayers.live >>> RedisGameRepository.layer
      case Backend.Cassandra =>
        CassandraSession.withSchemaLayer >>> CassandraGameRepository.layer

  private def cachedGameRepo(backend: Backend): TaskLayer[GameRepository] =
    val cacheLayer: ZLayer[Any, Throwable, CachedGameRepository.Cache] =
      (RedisLayers.live >>> RedisGameRepository.layer)
        .map(env =>
          ZEnvironment(CachedGameRepository.Cache(env.get[GameRepository]))
        )
    val primaryLayer: ZLayer[Any, Throwable, CachedGameRepository.Primary] =
      primaryGameRepo(backend).map(env =>
        ZEnvironment(CachedGameRepository.Primary(env.get[GameRepository]))
      )
    (cacheLayer ++ primaryLayer) >>> CachedGameRepository.layer

  // -- Lobby repository ------------------------------------------------------

  def lobbyRepository(cfg: BackendConfig): TaskLayer[LobbyRepository] =
    cfg.cache match
      case CacheBackend.NoCache => primaryLobbyRepo(cfg.backend)
      case CacheBackend.Redis   => cachedLobbyRepo(cfg.backend)

  private def primaryLobbyRepo(backend: Backend): TaskLayer[LobbyRepository] =
    backend match
      case Backend.InMemory  => InMemoryLobbyRepository.layer
      case Backend.Postgres  =>
        PostgresDatabase.withSchemaLayer >>> PostgresLobbyRepository.layer
      case Backend.Mongo     =>
        MongoClientLayer.layer >>> MongoLobbyRepository.withIndexesLayer
      case Backend.Redis     =>
        RedisLayers.live >>> RedisLobbyRepository.layer
      case Backend.Cassandra =>
        CassandraSession.withSchemaLayer >>> CassandraLobbyRepository.layer

  private def cachedLobbyRepo(
      backend: Backend
  ): TaskLayer[LobbyRepository] =
    val cacheLayer: ZLayer[Any, Throwable, CachedLobbyRepository.Cache] =
      (RedisLayers.live >>> RedisLobbyRepository.layer)
        .map(env =>
          ZEnvironment(CachedLobbyRepository.Cache(env.get[LobbyRepository]))
        )
    val primaryLayer: ZLayer[Any, Throwable, CachedLobbyRepository.Primary] =
      primaryLobbyRepo(backend).map(env =>
        ZEnvironment(CachedLobbyRepository.Primary(env.get[LobbyRepository]))
      )
    (cacheLayer ++ primaryLayer) >>> CachedLobbyRepository.layer
