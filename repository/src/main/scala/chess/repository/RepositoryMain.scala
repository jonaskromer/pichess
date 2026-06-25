package chess.repository

import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing

import chess.obs.{MetricsHttpServer, MetricsLayer, ProfilerLayer, TracingLayer, TracingMiddleware}
import chess.persistence.runtime.PersistenceLayers
import chess.persistence.{BackendConfig, GameArchiveRepository, GameRepository}

/** Standalone entry point for the repository microservice.
  *
  * Run with `sbt "repository/run"` or via Docker. The primary store is
  * selected by [[BackendConfig]] at startup — `PICHESS_BACKEND=inmemory` (the
  * default) for dev/test, `postgres` for durable persistence, and the other
  * backends as they come online.
  *
  * When `KAFKA_BOOTSTRAP_SERVERS` is set, also starts a Kafka consumer that
  * subscribes to `chess.game-events` and applies events as repo writes — the
  * long-term write path. When unset (e.g. `sbt repository/run` in dev without
  * a broker), only the HTTP REST surface is exposed; clients use `PUT
  * /games/{id}` directly. Both paths are idempotent.
  */
object RepositoryMain extends ZIOAppDefault:

  private[repository] val defaultPort = 8091
  private[repository] val defaultConsumerGroup = "pichess-repository"
  private val defaultMetricsPort = 9103

  override val bootstrap: ZLayer[Any, Nothing, Unit] =
    Runtime.enableRuntimeMetrics

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    ProfilerLayer.wrap(
      "repository",
      for
        port <- portFromEnv
        cfg  <- BackendConfig.fromEnv
        _    <- Console.printLine(
                  s"pichess-repository backend=${cfg.backend} cache=${cfg.cache}"
                )
        _    <- serve(port).provide(
                  gameRepoLayer(cfg),
                  PersistenceLayers.archiveRepository(cfg),
                  TracingLayer.fromEnv("repository")
                )
      yield ()
    )

  /** Read `REPOSITORY_PORT` via the ZIO system service so tests can swap it
    * out with `TestSystem.putEnv` instead of relying on the JVM's real env.
    */
  private[repository] def portFromEnv: Task[Int] =
    zio.System.env("REPOSITORY_PORT").map(parsePort)

  private[repository] def parsePort(envValue: Option[String]): Int =
    envValue.flatMap(_.toIntOption).getOrElse(defaultPort)

  /** Pick the `GameRepository` layer for the configured backend. Layer
    * selection lives in `PersistenceLayers` so all three services (game,
    * repository, lobby) stay in sync. The traced backend variant adds
    * a `Tracing` env requirement — fulfilled by the
    * `TracingLayer.fromEnv` provided further up the stack.
    */
  private[repository] def gameRepoLayer(
      cfg: BackendConfig
  ): ZLayer[Tracing, Throwable, GameRepository] =
    PersistenceLayers.gameRepository(cfg)

  private[repository] def serve(
      port: Int
  ): ZIO[
    GameRepository & GameArchiveRepository & Tracing & ContextStorage,
    Throwable,
    Unit
  ] =
    val program: ZIO[
      GameRepository & GameArchiveRepository & Server & Tracing &
        ContextStorage & Scope,
      Throwable,
      Unit,
    ] =
      for
        _           <- MetricsLayer.jvmMetricsBootstrap
        repo        <- ZIO.service[GameRepository]
        archiveRepo <- ZIO.service[GameArchiveRepository]
        eco         <- chess.opening.EcoBook.load
        bootstrap   <- zio.System.env("KAFKA_BOOTSTRAP_SERVERS")
        group       <- zio.System
                         .env("KAFKA_CONSUMER_GROUP")
                         .map(_.getOrElse(defaultConsumerGroup))
        metricsPort <- MetricsHttpServer.portFromEnv(defaultMetricsPort)
        _           <- Console.printLine(
                         s"pichess-repository listening on 0.0.0.0:$port " +
                           s"(metrics on 0.0.0.0:$metricsPort/metrics)"
                       )
        _           <- MetricsHttpServer.serve(metricsPort).forkDaemon
        _           <- bootstrap.filter(_.trim.nonEmpty) match
                         case Some(servers) =>
                           Console.printLine(
                             s"pichess-repository consuming chess.game-events from $servers (group=$group)"
                           ) *>
                             KafkaGameEventConsumer
                               .run(repo)
                               .provideSomeLayer(
                                 KafkaGameEventConsumer.consumerLayer(servers, group)
                               )
                               .forkDaemon *>
                             // Archive projection: its own consumer group so it
                             // tracks offsets independently of the FEN read-side.
                             KafkaGameArchiveConsumer
                               .run(archiveRepo)
                               .provideSomeLayer(
                                 KafkaGameArchiveConsumer
                                   .consumerLayer(servers, s"$group-archive")
                               )
                               .forkDaemon *>
                             Server.serve(
                               RepositoryServer.routes(repo, archiveRepo, eco)
                                 @@ TracingMiddleware.serverSpan
                             )
                         case None =>
                           Server.serve(
                             RepositoryServer.routes(repo, archiveRepo, eco)
                               @@ TracingMiddleware.serverSpan
                           )
      yield ()

    ZIO.scoped {
      program.provideSomeLayer[
        GameRepository & GameArchiveRepository & Tracing & ContextStorage & Scope
      ](Server.defaultWithPort(port))
    }
