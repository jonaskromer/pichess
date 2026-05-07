package chess.persistence.cassandra

import com.datastax.oss.driver.api.core.{CqlSession, CqlSessionBuilder}
import zio.*

import java.net.InetSocketAddress

/** Connection-pooled CQL session wired from `PICHESS_CASSANDRA_*` env vars.
  * One session per service; the underlying connection pool the driver
  * manages is shared across all queries. Closed when the surrounding ZIO
  * scope ends.
  */
object CassandraSession:

  val EnvContactPoints: String = "PICHESS_CASSANDRA_CONTACT_POINTS"
  val EnvDatacenter: String = "PICHESS_CASSANDRA_DC"
  val EnvKeyspace: String = "PICHESS_CASSANDRA_KEYSPACE"

  final case class Settings(
      contactPoints: List[InetSocketAddress],
      datacenter: String,
      keyspace: String
  )

  def settingsFromEnv: Task[Settings] =
    for
      raw  <- zio.System.env(EnvContactPoints).map(_.getOrElse("localhost:9042"))
      cps  <- ZIO.attempt(parseContactPoints(raw))
      dc   <- zio.System.env(EnvDatacenter).map(_.getOrElse("datacenter1"))
      ks   <- zio.System.env(EnvKeyspace).map(_.getOrElse("pichess"))
    yield Settings(cps, dc, ks)

  private[cassandra] def parseContactPoints(
      raw: String
  ): List[InetSocketAddress] =
    raw
      .split(',')
      .toList
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { hostPort =>
        hostPort.split(':') match
          case Array(h, p) => InetSocketAddress(h, p.toInt)
          case Array(h)    => InetSocketAddress(h, 9042)
          case other =>
            throw IllegalArgumentException(
              s"Malformed contact point: ${other.mkString(":")}"
            )
      }

  /** Build a scoped session against the configured cluster. The keyspace is
    * NOT bound at session-build time so the bootstrap CQL can `CREATE
    * KEYSPACE IF NOT EXISTS` before switching to it.
    */
  def make(settings: Settings): ZIO[Scope, Throwable, CqlSession] =
    val acquire = ZIO.attempt {
      val builder = CqlSession.builder()
      settings.contactPoints.foreach(cp => builder.addContactPoint(cp))
      builder.withLocalDatacenter(settings.datacenter)
      builder.build()
    }
    ZIO.acquireRelease(acquire)(s => ZIO.attempt(s.close()).orDie)

  /** A "ready" session: keyspace + tables created if missing, switched to
    * the configured keyspace. Used by service Mains.
    */
  val withSchemaLayer: ZLayer[Any, Throwable, CqlSession] =
    ZLayer.scoped {
      for
        settings <- settingsFromEnv
        session  <- make(settings)
        _        <- CassandraSchema.ensure(session, settings.keyspace)
        _        <- ZIO.attempt(session.execute(s"USE ${settings.keyspace}"))
      yield session
    }
