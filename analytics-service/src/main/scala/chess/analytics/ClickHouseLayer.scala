package chess.analytics

import zio.*
import zio.jdbc.*

import java.sql.{Connection, DriverManager}

/** Connection-pool wiring for ClickHouse via zio-jdbc. Settings come from
  * `PICHESS_CLICKHOUSE_*` env vars; the pool itself is scoped, so closing
  * the surrounding ZIO scope tears it down.
  *
  * No Slick involvement here — append-only inserts plus a handful of
  * aggregate SELECTs is exactly what zio-jdbc + raw SQL is best at. zio-jdbc
  * 0.1.x doesn't ship a `clickhouse(...)` factory, so we use the generic
  * `ZConnectionPool.make` with a hand-built JDBC `Connection` factory.
  */
object ClickHouseLayer:

  val EnvUrl: String = "PICHESS_CLICKHOUSE_URL"
  val EnvUser: String = "PICHESS_CLICKHOUSE_USER"
  val EnvPassword: String = "PICHESS_CLICKHOUSE_PASSWORD"

  final case class Settings(url: String, user: String, password: String)

  def settingsFromEnv: Task[Settings] =
    for
      url      <- zio.System
                    .env(EnvUrl)
                    .map(_.getOrElse("jdbc:clickhouse://localhost:8123/default"))
      user     <- zio.System.env(EnvUser).map(_.getOrElse("default"))
      password <- zio.System.env(EnvPassword).map(_.getOrElse(""))
    yield Settings(url, user, password)

  /** Open a fresh JDBC connection on demand. Each invocation forces the
    * ClickHouse driver class to load before requesting a connection — saves
    * us a confusing "no suitable driver" the first time the pool is hit
    * with a stale class loader.
    */
  def openConnection(settings: Settings): Task[Connection] =
    ZIO.attempt {
      Class.forName("com.clickhouse.jdbc.ClickHouseDriver")
      DriverManager.getConnection(settings.url, settings.user, settings.password)
    }

  /** Fully-wired pool layer used by service Mains. */
  val pool: ZLayer[Any, Throwable, ZConnectionPool] =
    val configLayer = ZLayer.succeed(ZConnectionPoolConfig.default)
    val poolLayer = ZConnectionPool.make(
      settingsFromEnv.flatMap(openConnection)
    )
    configLayer >>> poolLayer
