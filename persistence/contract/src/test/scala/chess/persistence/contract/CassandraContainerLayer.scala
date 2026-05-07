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

  /** Pre-configured CqlSession backed by a running Cassandra container. */
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
        session <- ZIO.acquireRelease(
                     ZIO.attempt(
                       CqlSession
                         .builder()
                         .addContactPoint(InetSocketAddress(host, port))
                         .withLocalDatacenter(dc)
                         .build()
                     )
                   )(s => ZIO.attempt(s.close()).orDie)
        _ <- CassandraSchema.ensure(session, keyspace)
        _ <- ZIO.attempt(
               session.execute(SimpleStatement.newInstance(s"USE $keyspace"))
             )
      yield session
    }
