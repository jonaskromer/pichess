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
// Implicit `Optimisation[PostgresDatabase]` instance — controls whether
// the HikariCP pool (`default`) or the original `Database.forURL` path
// (`baseline`) is used. Selection happens via the PICHESS_OPT_PG_POOL
// env var. See PostgresDatabaseOptimisations + docs/perf-experiments.md.
import chess.persistence.postgres.PostgresDatabaseOptimisations.given
import chess.persistence.redis.{
  RedisGameRepository,
  RedisLayers,
  RedisLobbyRepository
}
import chess.opt.Optimisation
import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing

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

  /** Returns a `GameRepository` layer for the configured backend,
    * wrapped with [[TracedGameRepository]] so each repo call surfaces
    * as a `db.game-repo.<op>` span in Jaeger. Adds `Tracing` to the
    * env requirement — service Mains already provide it via
    * `TracingLayer.fromEnv(...)`.
    */
  def gameRepository(
      cfg: BackendConfig
  ): ZLayer[Tracing, Throwable, GameRepository] =
    val inner = cfg.cache match
      case CacheBackend.NoCache => primaryGameRepo(cfg.backend)
      case CacheBackend.Redis   => cachedGameRepo(cfg.backend)
    TracedGameRepository.wrap(inner)

  private def primaryGameRepo(backend: Backend): TaskLayer[GameRepository] =
    backend match
      case Backend.InMemory  => InMemoryGameRepository.layer
      case Backend.Postgres  =>
        Optimisation.select[PostgresDatabase] >>> PostgresGameRepository.layer
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

  /** Same shape as [[gameRepository]] — wraps the selected backend with
    * tracing so lobby DB calls show up as `db.lobby-repo.<op>` spans.
    */
  def lobbyRepository(
      cfg: BackendConfig
  ): ZLayer[Tracing, Throwable, LobbyRepository] =
    val inner = cfg.cache match
      case CacheBackend.NoCache => primaryLobbyRepo(cfg.backend)
      case CacheBackend.Redis   => cachedLobbyRepo(cfg.backend)
    TracedLobbyRepository.wrap(inner)

  private def primaryLobbyRepo(backend: Backend): TaskLayer[LobbyRepository] =
    backend match
      case Backend.InMemory  => InMemoryLobbyRepository.layer
      case Backend.Postgres  =>
        Optimisation.select[PostgresDatabase] >>> PostgresLobbyRepository.layer
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
