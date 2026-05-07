package chess.persistence

import zio.*

/** Two-axis runtime config for the persistence stack.
  *
  *   - `backend` selects the primary store every repository writes through
  *   - `cache` optionally wraps the primary in a [[CachedGameRepository]]
  *     decorator (filled in once persistence-cache lands)
  *
  * Read once at service startup via [[BackendConfig.fromEnv]] and threaded
  * into the ZLayer that provides `GameRepository` / `LobbyRepository`.
  */
enum Backend:
  case InMemory
  case Postgres
  case Mongo
  case Redis
  case Cassandra

enum CacheBackend:
  case NoCache
  case Redis

final case class BackendConfig(backend: Backend, cache: CacheBackend)

object BackendConfig:

  val EnvBackend: String = "PICHESS_BACKEND"
  val EnvCache: String = "PICHESS_CACHE"

  /** Read-and-parse from process env. Unknown values fail loudly — a typo in
    * `PICHESS_BACKEND` should crash startup, not silently fall back.
    */
  def fromEnv: Task[BackendConfig] =
    for
      rawBackend <- zio.System.env(EnvBackend)
      rawCache   <- zio.System.env(EnvCache)
      backend    <- ZIO.fromEither(parseBackend(rawBackend))
      cache      <- ZIO.fromEither(parseCache(rawCache))
    yield BackendConfig(backend, cache)

  private[persistence] def parseBackend(
      raw: Option[String]
  ): Either[Throwable, Backend] =
    raw.map(_.trim.toLowerCase) match
      case None | Some("") | Some("inmemory") => Right(Backend.InMemory)
      case Some("postgres")                   => Right(Backend.Postgres)
      case Some("mongo")                      => Right(Backend.Mongo)
      case Some("redis")                      => Right(Backend.Redis)
      case Some("cassandra")                  => Right(Backend.Cassandra)
      case Some(other) =>
        Left(
          IllegalArgumentException(
            s"Unknown $EnvBackend value: '$other'. Expected one of: " +
              "inmemory, postgres, mongo, redis, cassandra."
          )
        )

  private[persistence] def parseCache(
      raw: Option[String]
  ): Either[Throwable, CacheBackend] =
    raw.map(_.trim.toLowerCase) match
      case None | Some("") | Some("none") | Some("nocache") =>
        Right(CacheBackend.NoCache)
      case Some("redis") => Right(CacheBackend.Redis)
      case Some(other) =>
        Left(
          IllegalArgumentException(
            s"Unknown $EnvCache value: '$other'. Expected one of: none, redis."
          )
        )
