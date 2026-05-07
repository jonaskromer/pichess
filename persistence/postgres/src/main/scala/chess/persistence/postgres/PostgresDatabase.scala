package chess.persistence.postgres

import slick.jdbc.PostgresProfile.api.*
import zio.*

import scala.concurrent.Future

/** Wraps a Slick `Database` so the rest of the module can call `run` without
  * passing it explicitly, and bridges Slick's `Future`-typed `DBIO` results
  * into ZIO without pulling in the (Scala 2.13-only) zio-slick-interop.
  */
final class PostgresDatabase(private val db: Database):

  /** Run a `DBIO[A]` against the underlying connection pool, lifting the
    * resulting `Future` into ZIO. Failures stay as `Throwable` here; callers
    * project them onto domain errors with `mapError`.
    */
  def run[A](action: DBIO[A]): Task[A] =
    ZIO.fromFuture(_ => db.run(action))

  /** Direct accessor for advanced cases (e.g. the schema-create at startup).
    * Prefer `run` for application-level queries.
    */
  def underlying: Database = db

object PostgresDatabase:

  final case class Settings(
      url: String,
      user: String,
      password: String,
      maxConnections: Int = 10
  )

  /** Read connection settings from the standard `PICHESS_PG_*` env vars. JDBC
    * URL is required; user / password default to the standard local-dev
    * 'postgres' values so the dev-compose stack works without env wiring.
    */
  def settingsFromEnv: Task[Settings] =
    for
      url      <- zio.System.env(EnvUrl).flatMap {
                    case Some(u) if u.trim.nonEmpty => ZIO.succeed(u)
                    case _ =>
                      ZIO.fail(
                        IllegalStateException(
                          s"$EnvUrl must be set when PICHESS_BACKEND=postgres"
                        )
                      )
                  }
      user     <- zio.System.env(EnvUser).map(_.getOrElse("postgres"))
      password <- zio.System.env(EnvPassword).map(_.getOrElse("postgres"))
      maxConn  <- zio.System
                    .env(EnvMaxConnections)
                    .map(_.flatMap(_.toIntOption).getOrElse(10))
    yield Settings(url, user, password, maxConn)

  val EnvUrl: String = "PICHESS_PG_URL"
  val EnvUser: String = "PICHESS_PG_USER"
  val EnvPassword: String = "PICHESS_PG_PASSWORD"
  val EnvMaxConnections: String = "PICHESS_PG_MAX_CONNECTIONS"

  /** Build a HikariCP-backed Slick `Database` from explicit settings. The
    * resource is scoped — closing the surrounding ZIO scope tears the pool
    * down.
    */
  def make(settings: Settings): ZIO[Scope, Throwable, PostgresDatabase] =
    ZIO.acquireRelease(
      ZIO.attempt(
        Database.forURL(
          url = settings.url,
          user = settings.user,
          password = settings.password,
          driver = "org.postgresql.Driver",
          executor = slick.util.AsyncExecutor(
            name = "pichess-pg",
            minThreads = settings.maxConnections,
            maxThreads = settings.maxConnections,
            queueSize = 1000,
            maxConnections = settings.maxConnections
          )
        )
      ).map(PostgresDatabase(_))
    )(pg => ZIO.attempt(pg.db.close()).orDie)

  /** Layer that reads settings from env and builds the database. Scoped: the
    * pool lifecycle matches the consumer service's scope.
    */
  val layer: ZLayer[Any, Throwable, PostgresDatabase] =
    ZLayer.scoped(settingsFromEnv.flatMap(make))

  /** Variant that also runs `PostgresSchema.ensure` so the database is ready
    * to accept queries the moment the layer resolves. Use this from service
    * Mains; the bare `layer` is reserved for tests that want to control
    * schema lifecycle themselves.
    */
  val withSchemaLayer: ZLayer[Any, Throwable, PostgresDatabase] =
    ZLayer.scoped {
      for
        settings <- settingsFromEnv
        db       <- make(settings)
        _        <- PostgresSchema.ensure(db)
      yield db
    }
