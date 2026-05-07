package chess.persistence.redis

import zio.*
import zio.redis.*
import zio.schema.Schema
import zio.schema.codec.{BinaryCodec, ProtobufCodec}

/** Wires the layers zio-redis needs into a single `Redis` service.
  *
  * Connection settings come from `PICHESS_REDIS_HOST` / `PICHESS_REDIS_PORT`
  * with sensible local-dev defaults. The protobuf codec is a fine choice for
  * our payloads — strings (FEN) and short JSON blobs (Lobby) — since
  * payload semantics are opaque to Redis itself.
  */
object RedisLayers:

  /** zio-redis takes a `CodecSupplier` to encode/decode arbitrary values; we
    * route every type through zio-schema's protobuf codec. zio-redis 1.1.x
    * doesn't ship this supplier out of the box (the example projects roll
    * their own), so we keep the few lines here. Public so tests in
    * persistence-contract can reuse it.
    */
  object ProtobufCodecSupplier extends CodecSupplier:
    def get[A](implicit schema: Schema[A]): BinaryCodec[A] =
      ProtobufCodec.protobufCodec[A]

    val layer: ULayer[CodecSupplier] = ZLayer.succeed(this)

  /** Composable layer fragment: takes a `RedisConfig` from upstream and
    * produces a `Redis` service. Combine with a Testcontainers-driven
    * `RedisConfig` layer for integration tests.
    */
  val fromConfig: ZLayer[RedisConfig, Throwable, Redis] =
    (ZLayer.environment[RedisConfig] ++ ProtobufCodecSupplier.layer) >>>
      Redis.singleNode

  val EnvHost: String = "PICHESS_REDIS_HOST"
  val EnvPort: String = "PICHESS_REDIS_PORT"

  private def configFromEnv: ZLayer[Any, Throwable, RedisConfig] =
    ZLayer.fromZIO {
      for
        host <- zio.System.env(EnvHost).map(_.getOrElse("localhost"))
        port <- zio.System
                  .env(EnvPort)
                  .map(_.flatMap(_.toIntOption).getOrElse(6379))
      yield RedisConfig(host, port)
    }

  /** Full `Redis` service layer wired from env-driven config. Uses the
    * Protobuf codec from zio-redis-codecs.
    */
  val live: ZLayer[Any, Throwable, Redis] =
    ZLayer.make[Redis](
      configFromEnv,
      Redis.singleNode,
      ProtobufCodecSupplier.layer
    )

  /** Layer driven by an explicit `RedisConfig` — for tests where the host
    * and port come from a Testcontainers-managed instance, not env.
    */
  def layerFor(config: RedisConfig): ZLayer[Any, Throwable, Redis] =
    ZLayer.make[Redis](
      ZLayer.succeed(config),
      Redis.singleNode,
      ProtobufCodecSupplier.layer
    )
