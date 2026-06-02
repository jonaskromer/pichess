package chess.gameservice

import chess.events.{
  GameEventProducer,
  InMemoryGameEventProducer,
  KafkaGameEventProducer
}
import chess.obs.{MetricsHttpServer, MetricsLayer, ProfilerLayer}
import chess.persistence.{BackendConfig, GameRepository}
import chess.persistence.runtime.PersistenceLayers
import chess.service.{GameService, GameServiceLive}
import io.grpc.ServerBuilder
import pichess.game_service.ZioGameService
import scalapb.zio_grpc.ServerLayer
import zio.*

/** Standalone entry point for the gameService microservice.
  *
  * Exposes a zio-grpc server on `GRPC_PORT` (default 9000), backed by:
  *   - The `GameRepository` impl selected by [[BackendConfig]]
  *     (`InMemoryGameRepository` by default; `PostgresGameRepository` when
  *     `PICHESS_BACKEND=postgres`; others as they come online)
  *   - `KafkaGameEventProducer` when `KAFKA_BOOTSTRAP_SERVERS` is set,
  *     `InMemoryGameEventProducer` otherwise (so dev runs without a broker)
  *
  * In-memory mode loses state on restart. Postgres mode is durable and
  * replays via Kafka on the read-side (the repository service).
  */
object GameServiceMain extends ZIOAppDefault:

  private val defaultPort = 9000
  private val defaultMetricsPort = 9102

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    ProfilerLayer.wrap(
      "game-service",
      for
        port <- portFromEnv
        cfg  <- BackendConfig.fromEnv
        _    <- Console.printLine(
                  s"pichess-game-service backend=${cfg.backend} cache=${cfg.cache}"
                )
        _    <- serve(port, cfg)
      yield ()
    )

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

  /** Pick the `GameRepository` layer for the configured backend. Selection
    * lives in `PersistenceLayers` — see [[chess.persistence.runtime.PersistenceLayers]].
    */
  private[gameservice] def gameRepoLayer(
      cfg: BackendConfig
  ): TaskLayer[GameRepository] =
    PersistenceLayers.gameRepository(cfg)

  private def serve(port: Int, cfg: BackendConfig): Task[Unit] =
    val program: ZIO[scalapb.zio_grpc.Server & Scope, Throwable, Unit] =
      for
        // Start JVM metric trackers — heap / GC / threads flow through
        // the Prometheus publisher so the persistence-experiment
        // resource-profile table has signal to read.
        _           <- MetricsLayer.jvmMetricsBootstrap
        metricsPort <- MetricsHttpServer.portFromEnv(defaultMetricsPort)
        _           <- Console.printLine(
                         s"pichess-game-service gRPC listening on 0.0.0.0:$port " +
                           s"(metrics on 0.0.0.0:$metricsPort/metrics)"
                       )
        _           <- MetricsHttpServer.serve(metricsPort).forkDaemon
        _           <- ZIO.service[scalapb.zio_grpc.Server].flatMap(_.awaitTermination)
      yield ()

    val producerLayer: ZLayer[Any, Throwable, GameEventProducer] =
      selectProducerLayer(sys.env.get("KAFKA_BOOTSTRAP_SERVERS"))

    ZIO.scoped {
      program.provideSome[Scope](
        gameRepoLayer(cfg),
        GameSessions.layer,
        GameServiceLive.layer,
        GrpcServer.asServiceLayer,
        producerLayer,
        ServerLayer.fromEnvironment[ZioGameService.GameService](
          ServerBuilder.forPort(port)
        )
      )
    }
