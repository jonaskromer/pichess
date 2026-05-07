package chess.opening

import org.neo4j.driver.{AuthTokens, Driver, GraphDatabase}
import zio.*

/** Connection-pooled Neo4j driver wired from `PICHESS_NEO4J_*` env vars.
  * Lifecycle is scoped — closing the surrounding ZIO scope tears the driver
  * down (and with it, the underlying connection pool).
  */
object Neo4jLayer:

  val EnvUrl: String = "PICHESS_NEO4J_URL"
  val EnvUser: String = "PICHESS_NEO4J_USER"
  val EnvPassword: String = "PICHESS_NEO4J_PASSWORD"

  final case class Settings(url: String, user: String, password: String)

  def settingsFromEnv: Task[Settings] =
    for
      url      <- zio.System.env(EnvUrl).map(_.getOrElse("bolt://localhost:7687"))
      user     <- zio.System.env(EnvUser).map(_.getOrElse("neo4j"))
      password <- zio.System.env(EnvPassword).map(_.getOrElse("password"))
    yield Settings(url, user, password)

  def make(settings: Settings): ZIO[Scope, Throwable, Driver] =
    ZIO.acquireRelease(
      ZIO.attempt(
        GraphDatabase.driver(
          settings.url,
          AuthTokens.basic(settings.user, settings.password)
        )
      )
    )(driver => ZIO.attempt(driver.close()).orDie)

  val layer: ZLayer[Any, Throwable, Driver] =
    ZLayer.scoped(settingsFromEnv.flatMap(make))
