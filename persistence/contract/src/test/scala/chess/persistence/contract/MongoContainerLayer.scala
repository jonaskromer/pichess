package chess.persistence.contract

import com.dimafeng.testcontainers.MongoDBContainer
import com.mongodb.reactivestreams.client.MongoDatabase
import org.testcontainers.utility.DockerImageName
import zio.*

import chess.persistence.mongo.MongoClientLayer

object MongoContainerLayer:

  private val Image: DockerImageName = DockerImageName.parse("mongo:7")

  val databaseLayer: ZLayer[Any, Throwable, MongoDatabase] =
    ZLayer.scoped {
      for
        container <- ZIO.acquireRelease(
                       ZIO.attempt {
                         val c = MongoDBContainer(Image)
                         c.start()
                         c
                       }
                     )(c => ZIO.attempt(c.stop()).orDie)
        db <- MongoClientLayer.make(
                MongoClientLayer.Settings(
                  url = container.replicaSetUrl,
                  database = "pichess-test"
                )
              )
      yield db
    }
