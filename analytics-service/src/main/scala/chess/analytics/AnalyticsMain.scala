package chess.analytics

import chess.obs.{MetricsHttpServer, ProfilerLayer, TracingLayer, TracingMiddleware}
import zio.*
import zio.http.*

/** Standalone entry point for the analytics-service microservice.
  *
  * Two concurrent jobs:
  *   - Kafka consumer that ingests `chess.game-events` into ClickHouse
  *   - HTTP server on `:8093` exposing canonical aggregate queries
  *
  * Required env: `KAFKA_BOOTSTRAP_SERVERS`, `PICHESS_CLICKHOUSE_URL`.
  * If Kafka is unset, the consumer is skipped — the service still serves
  * REST queries against any pre-existing ClickHouse data.
  */
object AnalyticsMain extends ZIOAppDefault:

  private[analytics] val defaultPort = 8093
  private val defaultConsumerGroup = "pichess-analytics"
  private val defaultMetricsPort = 9106

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    ProfilerLayer.wrap("analytics-service", runProfiled)

  private def runProfiled: ZIO[ZIOAppArgs, Throwable, Unit] =
    val program: ZIO[
      AnalyticsProjection & AnalyticsService & zio.jdbc.ZConnectionPool,
      Throwable,
      Unit
    ] =
      for
        port        <- portFromEnv
        bootstrap   <- zio.System.env("KAFKA_BOOTSTRAP_SERVERS")
        group       <- zio.System
                         .env("KAFKA_CONSUMER_GROUP")
                         .map(_.getOrElse(defaultConsumerGroup))
        _           <- AnalyticsSchema.ensure
        svc         <- ZIO.service[AnalyticsService]
        proj        <- ZIO.service[AnalyticsProjection]
        metricsPort <- MetricsHttpServer.portFromEnv(defaultMetricsPort)
        _           <- Console.printLine(
                         s"pichess-analytics-service listening on 0.0.0.0:$port " +
                           s"(metrics on 0.0.0.0:$metricsPort/metrics)"
                       )
        _           <- MetricsHttpServer.serve(metricsPort).forkDaemon
        _           <- bootstrap.filter(_.trim.nonEmpty) match
                         case Some(servers) =>
                           Console.printLine(
                             s"pichess-analytics-service consuming chess.game-events from $servers (group=$group)"
                           ) *>
                             KafkaAnalyticsConsumer
                               .run(proj)
                               .provideSomeLayer[zio.jdbc.ZConnectionPool](
                                 KafkaAnalyticsConsumer
                                   .consumerLayer(servers, group)
                               )
                               .forkDaemon *>
                             Server
                               .serve(
                                 AnalyticsServer.routes(svc)
                                   @@ TracingMiddleware.serverSpan
                               )
                               .provide(
                                 Server.defaultWithPort(port),
                                 TracingLayer.fromEnv("analytics-service"),
                               )
                         case None =>
                           Server
                             .serve(
                               AnalyticsServer.routes(svc)
                                 @@ TracingMiddleware.serverSpan
                             )
                             .provide(
                               Server.defaultWithPort(port),
                               TracingLayer.fromEnv("analytics-service"),
                             )
      yield ()

    program.provide(
      ClickHouseLayer.pool,
      LiveAnalyticsService.layer,
      AnalyticsProjection.layer
    )

  private[analytics] def portFromEnv: Task[Int] =
    zio.System.env("ANALYTICS_PORT").map(parsePort)

  private[analytics] def parsePort(envValue: Option[String]): Int =
    envValue.flatMap(_.toIntOption).getOrElse(defaultPort)
