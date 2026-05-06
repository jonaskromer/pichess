package chess.gameservice

import chess.events.{
  GameEventProducer,
  InMemoryGameEventProducer,
  KafkaGameEventProducer
}
import chess.service.{GameService, GameServiceLive}
import io.grpc.ServerBuilder
import pichess.game_service.ZioGameService
import scalapb.zio_grpc.ServerLayer
import zio.*

/** Standalone entry point for the gameService microservice.
  *
  * Exposes a zio-grpc server on `GRPC_PORT` (default 9000), backed by:
  *   - `InMemoryGameStore` for in-flight game state
  *   - `KafkaGameEventProducer` when `KAFKA_BOOTSTRAP_SERVERS` is set,
  *     `InMemoryGameEventProducer` otherwise (so dev runs without a broker)
  *
  * State is non-durable; restart drops every active game. Replay-from-Kafka
  * on startup is the documented next iteration.
  */
object GameServiceMain extends ZIOAppDefault:

  private val defaultPort = 9000

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    portFromEnv.flatMap(serve)

  private[gameservice] def portFromEnv: Task[Int] =
    zio.System.env("GRPC_PORT").map(parsePort)

  private[gameservice] def parsePort(envValue: Option[String]): Int =
    envValue.flatMap(_.toIntOption).getOrElse(defaultPort)

  private[gameservice] def selectProducerLayer(
      envBootstrap: Option[String]
  ): ZLayer[Any, Throwable, GameEventProducer] =
    envBootstrap.filter(_.trim.nonEmpty) match
      case Some(servers) => KafkaGameEventProducer.layer(servers)
      case None          => InMemoryGameEventProducer.layer

  private val producerLayer: ZLayer[Any, Throwable, GameEventProducer] =
    selectProducerLayer(sys.env.get("KAFKA_BOOTSTRAP_SERVERS"))

  private def serve(port: Int): Task[Unit] =
    val program: ZIO[scalapb.zio_grpc.Server, Throwable, Unit] =
      Console.printLine(
        s"pichess-game-service gRPC listening on 0.0.0.0:$port"
      ) *> ZIO.service[scalapb.zio_grpc.Server].flatMap(_.awaitTermination)

    program.provide(
      InMemoryGameStore.layer,
      GameSessions.layer,
      GameServiceLive.layer,
      GrpcServer.asServiceLayer,
      producerLayer,
      ServerLayer.fromEnvironment[ZioGameService.GameService](
        ServerBuilder.forPort(port)
      )
    )
