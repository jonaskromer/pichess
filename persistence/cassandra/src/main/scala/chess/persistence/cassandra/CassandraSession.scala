package chess.persistence.cassandra

import com.datastax.oss.driver.api.core.{CqlSession, CqlSessionBuilder}
import com.datastax.oss.driver.api.core.config.{DefaultDriverOption, DriverConfigLoader}
import zio.*

import java.net.InetSocketAddress
import java.time.Duration as JDuration

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

  /** Request timeout for CQL statements. The DataStax driver defaults to
    * 2 seconds, which is fine for steady-state queries but too tight for
    * (a) Testcontainers cold-start when a fresh Cassandra container is
    * still bootstrapping its system keyspaces, and (b) coverage-instrumented
    * runs where scoverage's per-statement `Invoker.invoked` writes slow
    * the netty event loop enough that the sync wait crosses 2 s. 10 s
    * covers both without masking a genuine network or cluster problem.
    */
  private[cassandra] val requestTimeout: JDuration = JDuration.ofSeconds(10)

  private val driverConfig: DriverConfigLoader =
    DriverConfigLoader
      .programmaticBuilder()
      .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, requestTimeout)
      .build()

  /** Build a scoped session against the configured cluster. The keyspace is
    * NOT bound at session-build time so the bootstrap CQL can `CREATE
    * KEYSPACE IF NOT EXISTS` before switching to it.
    */
  def make(settings: Settings): ZIO[Scope, Throwable, CqlSession] =
    val acquire = ZIO.attempt {
      val builder = CqlSession.builder()
      settings.contactPoints.foreach(cp => builder.addContactPoint(cp))
      builder.withLocalDatacenter(settings.datacenter)
      builder.withConfigLoader(driverConfig)
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
