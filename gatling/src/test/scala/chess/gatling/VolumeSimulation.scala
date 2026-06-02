package chess.gatling

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

import scala.concurrent.duration.*

/** Volume test: a large open-loop ramp of distinct game sessions, each
  * running the full [[Chains.playOneGame]] flow. The aim is to stress
  * the *storage* layer — index growth, page splits, compaction triggers,
  * Cassandra tombstone accumulation — rather than per-request latency
  * under load. Each completed user adds one game (9 writes + 1 read) to
  * whichever backend is active.
  *
  * Suggested invocation populates ~500 games:
  *   sbt -DpichessPeakUsers=500 -DpichessRampSeconds=60 \
  *       'gatling/Gatling/testOnly chess.gatling.VolumeSimulation'
  */
class VolumeSimulation extends Simulation:

  private val httpProtocol = http
    .baseUrl(SharedConfig.gatewayUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private val scn = scenario("volume").exec(Chains.playOneGame)

  setUp(
    scn.inject(
      rampUsers(SharedConfig.peakUsers)
        .during(SharedConfig.rampSeconds.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(
      // Volume is about completion, not latency — relax response-time
      // SLAs but keep the error rate tight: we still want every game
      // to make it through cleanly even when the DB is grinding.
      global.responseTime.percentile3.lt(2000),
      global.failedRequests.percent.lt(2.0),
    )
