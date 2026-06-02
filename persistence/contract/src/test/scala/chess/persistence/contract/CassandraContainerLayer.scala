package chess.persistence.contract

import chess.persistence.cassandra.{CassandraSchema, CassandraSession}
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.dimafeng.testcontainers.CassandraContainer
import org.testcontainers.utility.DockerImageName
import zio.*

import java.net.InetSocketAddress

object CassandraContainerLayer:

  private val Image: DockerImageName =
    DockerImageName.parse("cassandra:4.1")

  /** Pre-configured CqlSession backed by a running Cassandra container.
    * Delegates session construction to [[CassandraSession.make]] so the
    * request-timeout config it installs (bumped above the driver's
    * 2-second default to survive coverage-instrumented runs) is shared
    * with production code — single source of truth for driver
    * configuration.
    */
  val sessionLayer: ZLayer[Any, Throwable, CqlSession] =
    ZLayer.scoped {
      for
        container <- ZIO.acquireRelease(
                       ZIO.attempt {
                         val c = CassandraContainer(Image)
                         c.start()
                         c
                       }
                     )(c => ZIO.attempt(c.stop()).orDie)
        host = container.host
        port = container.firstMappedPort
        dc   = container.container.getLocalDatacenter
        keyspace = "pichess_test"
        settings  = CassandraSession.Settings(
                      contactPoints = List(InetSocketAddress(host, port)),
                      datacenter    = dc,
                      keyspace      = keyspace
                    )
        session <- CassandraSession.make(settings)
        _       <- CassandraSchema.ensure(session, keyspace)
        _       <- ZIO.attempt(
                     session.execute(SimpleStatement.newInstance(s"USE $keyspace"))
                   )
      yield session
    }
