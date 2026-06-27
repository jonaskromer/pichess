package chess.gatling

import scala.concurrent.duration.*

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

/** Direct load on the **archive store** — the mongo/redis persistence path the
  * gameplay flow only reaches indirectly (via Kafka → `GameArchiver`). Each
  * virtual user `POST /archives` a distinct finished game, then `GET
  * /archives/{id}` reads it back, isolating `MongoGameArchiveRepository` /
  * `RedisGameArchiveRepository` write+read latency from the gateway + engine.
  *
  * Targets the **repository service** directly (`pichessRepositoryUrl`, default
  * :8091), not the gateway — so flip `PICHESS_BACKEND` on the repository service
  * (mongo vs redis) and compare. The write replays the submitted UCI to rebuild
  * SAN/PGN + name the opening, so this also stresses that server-side replay.
  *
  *   sbt -DpichessRepositoryUrl=http://localhost:8091 \
  *       -DpichessPeakUsers=100 -DpichessRampSeconds=20 \
  *       -DpichessHoldSeconds=120 -DpichessRatePerSec=20 \
  *       'gatling/Gatling/testOnly chess.gatling.ArchiveSimulation'
  */
class ArchiveSimulation extends Simulation:

  private val httpProtocol = http
    .baseUrl(SharedConfig.repositoryUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private val scn = scenario("archive-write-read").exec(Chains.submitAndReadArchive)

  setUp(
    scn.inject(
      rampUsers(SharedConfig.peakUsers)
        .during(SharedConfig.rampSeconds.seconds),
      constantUsersPerSec(SharedConfig.ratePerSec.toDouble)
        .during(SharedConfig.holdSeconds.seconds),
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile3.lt(500),
      global.responseTime.percentile4.lt(2000),
      global.failedRequests.percent.lt(1.0),
    )
