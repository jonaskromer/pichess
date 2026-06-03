package chess.persistence.contract

import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.redis.{Redis, RedisConfig}

import chess.persistence.redis.RedisLayers

object RedisContainerLayer:

  private val Image: DockerImageName = DockerImageName.parse("redis:7-alpine")
  private val RedisPort: Int = 6379

  private val containerConfig: ZLayer[Any, Throwable, RedisConfig] =
    ZLayer.scoped {
      for
        container <- ZIO.acquireRelease(
                       ZIO.attempt {
                         val c = GenericContainer(
                           dockerImage = Image.asCanonicalNameString,
                           exposedPorts = Seq(RedisPort),
                           waitStrategy = Wait.forListeningPort()
                         )
                         c.start()
                         c
                       }
                     )(c => ZIO.attempt(c.stop()).orDie)
        host = container.host
        port = container.mappedPort(RedisPort)
      yield RedisConfig(host, port)
    }

  val redisLayer: ZLayer[Any, Throwable, Redis] =
    containerConfig >>> RedisLayers.fromConfig
