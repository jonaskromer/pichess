package chess.persistence.contract

import com.dimafeng.testcontainers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import zio.*

import chess.persistence.postgres.{PostgresDatabase, PostgresSchema}

/** Builds a fully-prepared `PostgresDatabase` layer backed by a Testcontainers
  * PostgreSQL instance. The container is started on layer construction and
  * stopped when the surrounding scope closes; schema is created so the
  * database is immediately usable.
  *
  * Each call to `databaseLayer` resolves to a fresh container — backends use
  * one per contract suite to avoid cross-suite id collisions.
  */
object PostgresContainerLayer:

  /** Pinned for deterministic CI; bump deliberately, not opportunistically. */
  private val Image: DockerImageName =
    DockerImageName.parse("postgres:16-alpine")

  val databaseLayer: ZLayer[Any, Throwable, PostgresDatabase] =
    ZLayer.scoped {
      for
        container <- ZIO.acquireRelease(
                       ZIO.attempt {
                         val c = PostgreSQLContainer(
                           dockerImageNameOverride = Image
                         )
                         c.start()
                         c
                       }
                     )(c => ZIO.attempt(c.stop()).orDie)
        settings = PostgresDatabase.Settings(
                     url = container.jdbcUrl,
                     user = container.username,
                     password = container.password
                   )
        db       <- PostgresDatabase.make(settings)
        _        <- PostgresSchema.ensure(db)
      yield db
    }
