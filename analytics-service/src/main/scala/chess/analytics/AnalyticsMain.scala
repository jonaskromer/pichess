package chess.analytics

import zio.*
import zio.http.*

import chess.events.Topics
import chess.obs.{MetricsHttpServer, MetricsLayer, ProfilerLayer, TracingLayer, TracingMiddleware}

/** Standalone entry point for the analytics-service microservice.
  *
  * Two concurrent jobs:
  *   - Kafka consumer that folds `chess.analytics` (Spark speed-layer
  *     per-game summaries) into in-memory aggregate state
  *   - HTTP server on `:8093` exposing canonical aggregate queries
  *
  * Required env: `KAFKA_BOOTSTRAP_SERVERS`. If Kafka is unset the consumer is
  * skipped and the service serves empty aggregates. No database (ADR 022).
  */
object AnalyticsMain extends ZIOAppDefault:

  private[analytics] val defaultPort = 8093
  private val defaultConsumerGroup = "pichess-analytics"
  private val defaultMetricsPort = 9106

  override val bootstrap: ZLayer[Any, Nothing, Unit] =
    Runtime.enableRuntimeMetrics

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    ProfilerLayer.wrap("analytics-service", runProfiled)

  private def serveHttp(port: Int, svc: AnalyticsService): Task[Nothing] =
    Server
      .serve(AnalyticsServer.routes(svc) @@ TracingMiddleware.serverSpan)
      .provide(
        Server.defaultWithPort(port),
        TracingLayer.fromEnv("analytics-service")
      )

  private def runProfiled: ZIO[ZIOAppArgs, Throwable, Unit] =
    val program: ZIO[AnalyticsService & Scope, Throwable, Unit] =
      for
        _           <- MetricsLayer.jvmMetricsBootstrap
        port        <- portFromEnv
        bootstrap   <- zio.System.env("KAFKA_BOOTSTRAP_SERVERS")
        group       <- zio.System
                         .env("KAFKA_CONSUMER_GROUP")
                         .map(_.getOrElse(defaultConsumerGroup))
        svc         <- ZIO.service[AnalyticsService]
        metricsPort <- MetricsHttpServer.portFromEnv(defaultMetricsPort)
        _           <- Console.printLine(
                         s"pichess-analytics-service listening on 0.0.0.0:$port " +
                           s"(metrics on 0.0.0.0:$metricsPort/metrics)"
                       )
        _           <- MetricsHttpServer.serve(metricsPort).forkDaemon
        _           <- bootstrap.filter(_.trim.nonEmpty) match
                         case Some(servers) =>
                           Console.printLine(
                             s"pichess-analytics-service consuming ${Topics.Analytics} from $servers (group=$group)"
                           ) *>
                             KafkaAnalyticsConsumer
                               .run(svc)
                               .provideLayer(
                                 KafkaAnalyticsConsumer.consumerLayer(servers, group)
                               )
                               .forkDaemon *>
                             serveHttp(port, svc)
                         case None =>
                           serveHttp(port, svc)
      yield ()

    ZIO.scoped {
      program.provideSome[Scope](LiveAnalyticsService.layer)
    }

  private[analytics] def portFromEnv: Task[Int] =
    zio.System.env("ANALYTICS_PORT").map(parsePort)

  private[analytics] def parsePort(envValue: Option[String]): Int =
    envValue.flatMap(_.toIntOption).getOrElse(defaultPort)
