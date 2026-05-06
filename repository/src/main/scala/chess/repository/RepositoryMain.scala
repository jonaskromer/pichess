package chess.repository

import zio.*
import zio.http.*

/** Standalone entry point for the repository microservice.
  *
  * Run with `sbt "repository/run"` or via Docker. Backed by
  * [[InMemoryGameRepository]]; swap to a persistent impl by changing the
  * `GameRepository` layer provided in `serve`.
  *
  * When `KAFKA_BOOTSTRAP_SERVERS` is set, also starts a Kafka consumer that
  * subscribes to `chess.game-events` and applies events as repo writes — this
  * is the long-term write path. When unset (e.g. `sbt repository/run` in dev
  * without a broker), only the HTTP REST surface is exposed; clients use
  * `PUT /games/{id}` directly. The two paths coexist during the strangler
  * migration (step 4); both are idempotent.
  */
object RepositoryMain extends ZIOAppDefault:

  private[repository] val defaultPort = 8091
  private[repository] val defaultConsumerGroup = "pichess-repository"

  override def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    portFromEnv.flatMap(serve)

  /** Read `REPOSITORY_PORT` via the ZIO system service so tests can swap it out
    * with `TestSystem.putEnv` instead of relying on the JVM's real env.
    */
  private[repository] def portFromEnv: Task[Int] =
    zio.System.env("REPOSITORY_PORT").map(parsePort)

  private[repository] def parsePort(envValue: Option[String]): Int =
    envValue.flatMap(_.toIntOption).getOrElse(defaultPort)

  private[repository] def serve(port: Int): Task[Unit] =
    (for
      repo      <- ZIO.service[GameRepository]
      bootstrap <- zio.System.env("KAFKA_BOOTSTRAP_SERVERS")
      group     <- zio.System
                     .env("KAFKA_CONSUMER_GROUP")
                     .map(_.getOrElse(defaultConsumerGroup))
      _         <- Console.printLine(
                     s"pichess-repository listening on 0.0.0.0:$port"
                   )
      _         <- bootstrap.filter(_.trim.nonEmpty) match
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
                         Server.serve(RepositoryServer.routes(repo))
                     case None =>
                       Server.serve(RepositoryServer.routes(repo))
    yield ()).provide(
      InMemoryGameRepository.layer,
      Server.defaultWithPort(port)
    )
