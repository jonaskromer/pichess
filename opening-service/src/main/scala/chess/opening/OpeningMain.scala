package chess.opening

import chess.obs.{MetricsHttpServer, MetricsLayer, ProfilerLayer}
import zio.*

/** Standalone entry point for the opening-service microservice.
  *
  * Pure projector — no HTTP surface in Phase 1. Subscribes to the
  * `chess.game-events` Kafka topic and walks each MoveMade into Neo4j as
  * a (Position)-[:MOVE]->(Position) edge with a running count.
  *
  * Required env:
  *   - `KAFKA_BOOTSTRAP_SERVERS` (otherwise the consumer never starts)
  *   - `PICHESS_NEO4J_URL` (defaults to bolt://localhost:7687 in dev)
  */
object OpeningMain extends ZIOAppDefault:

  private val defaultConsumerGroup = "pichess-opening"
  private val defaultMetricsPort = 9105

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    ProfilerLayer.wrap("opening-service", runProfiled)

  private def runProfiled: ZIO[ZIOAppArgs, Throwable, Unit] =
    val program: ZIO[OpeningProjection & Scope, Throwable, Unit] =
      for
        _           <- MetricsLayer.jvmMetricsBootstrap
        bootstrap   <- zio.System.env("KAFKA_BOOTSTRAP_SERVERS")
        group       <- zio.System
                         .env("KAFKA_CONSUMER_GROUP")
                         .map(_.getOrElse(defaultConsumerGroup))
        projection  <- ZIO.service[OpeningProjection]
        metricsPort <- MetricsHttpServer.portFromEnv(defaultMetricsPort)
        _           <- Console.printLine(
                         s"pichess-opening-service metrics on 0.0.0.0:$metricsPort/metrics"
                       )
        _           <- MetricsHttpServer.serve(metricsPort).forkDaemon
        _           <- bootstrap.filter(_.trim.nonEmpty) match
                        case Some(servers) =>
                          Console.printLine(
                            s"pichess-opening-service consuming chess.game-events from $servers (group=$group)"
                          ) *>
                            KafkaOpeningConsumer
                              .run(projection)
                              .provideSomeLayer(
                                KafkaOpeningConsumer
                                  .consumerLayer(servers, group)
                              )
                        case None =>
                          Console.printLine(
                            "KAFKA_BOOTSTRAP_SERVERS not set; opening-service has nothing to project. Idling."
                          ) *> ZIO.never
      yield ()

    ZIO.scoped {
      program.provideSome[Scope](
        Neo4jLayer.layer,
        Neo4jOpeningTree.layer,
        OpeningProjection.layer
      )
    }
