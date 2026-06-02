package chess.persistence.postgres

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
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

  /** Build a Slick `Database` backed by `Database.forURL`, which uses
    * `DriverDataSource` under the hood — **a fresh JDBC connection is
    * opened per query**. The `AsyncExecutor` controls the *thread*
    * pool but not the *connection* pool (per Slick docs: "numThreads
    * has no effect on the number of connections in the connection
    * pool"). This is the baseline path for the `PG_POOL` optimisation;
    * see [[makeHikari]] for the pooled alternative.
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

  /** Build a Slick `Database` backed by an explicit HikariCP
    * `DataSource`. Connections are pooled and reused, so SCRAM/PBKDF2
    * authentication runs once per pool slot rather than per query —
    * this is the `default` arm of the `PG_POOL` optimisation.
    *
    * Scoped lifecycle: when the surrounding ZIO scope closes, the
    * Slick `Database` and the HikariCP pool are both shut down.
    */
  def makeHikari(settings: Settings): ZIO[Scope, Throwable, PostgresDatabase] =
    for
      ds <- ZIO.acquireRelease(
              ZIO.attempt {
                val cfg = new HikariConfig()
                cfg.setJdbcUrl(settings.url)
                cfg.setUsername(settings.user)
                cfg.setPassword(settings.password)
                cfg.setMaximumPoolSize(settings.maxConnections)
                cfg.setPoolName("pichess-pg-hikari")
                // Tight init / fail-fast so a misconfigured stack errors
                // at startup rather than on the first request. Defaults
                // for everything else.
                cfg.setInitializationFailTimeout(5_000L)
                new HikariDataSource(cfg)
              }
            )(ds => ZIO.attempt(ds.close()).orDie)
      db <- ZIO.acquireRelease(
              ZIO.attempt(
                Database.forDataSource(
                  ds = ds,
                  maxConnections = Some(settings.maxConnections),
                  executor = slick.util.AsyncExecutor(
                    name = "pichess-pg-hikari",
                    minThreads = settings.maxConnections,
                    maxThreads = settings.maxConnections,
                    queueSize = 1000,
                    maxConnections = settings.maxConnections
                  ),
                  keepAliveConnection = false
                )
              ).map(PostgresDatabase(_))
            )(pg => ZIO.attempt(pg.db.close()).orDie)
    yield db

  /** Baseline layer (no connection pooling). Reads settings from env
    * and builds the database; pool lifecycle matches the consumer
    * service's scope.
    */
  val layer: ZLayer[Any, Throwable, PostgresDatabase] =
    ZLayer.scoped(settingsFromEnv.flatMap(make))

  /** Default layer: HikariCP-backed. Same env vars, same lifecycle —
    * just connection pooling. This is the layer the `Optimisation`
    * instance selects unless `PICHESS_OPT_PG_POOL=baseline` (or the
    * global `PICHESS_OPT_ALL=baseline`) flips it.
    */
  val hikariLayer: ZLayer[Any, Throwable, PostgresDatabase] =
    ZLayer.scoped(settingsFromEnv.flatMap(makeHikari))

  /** Baseline + schema migration. Use this from service Mains when the
    * baseline (no-pool) path is selected; the bare `layer` is reserved
    * for tests that control schema lifecycle themselves.
    */
  val withSchemaLayer: ZLayer[Any, Throwable, PostgresDatabase] =
    ZLayer.scoped {
      for
        settings <- settingsFromEnv
        db       <- make(settings)
        _        <- PostgresSchema.ensure(db)
      yield db
    }

  /** Default + schema migration. The hikariLayer counterpart of
    * [[withSchemaLayer]] — bootstrap the HikariCP pool and run the
    * schema migration in one scoped layer.
    */
  val withSchemaLayerHikari: ZLayer[Any, Throwable, PostgresDatabase] =
    ZLayer.scoped {
      for
        settings <- settingsFromEnv
        db       <- makeHikari(settings)
        _        <- PostgresSchema.ensure(db)
      yield db
    }
