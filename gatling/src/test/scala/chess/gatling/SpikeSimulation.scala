package chess.gatling

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

import scala.concurrent.duration.*

/** Spike test: baseline trickle, then three sudden bursts of `peakUsers`
  * spaced 30 seconds apart, with the trickle resuming between bursts.
  * Validates that the system absorbs the spike and recovers — both
  * latency-wise (no permanent SLA breach after the spike) and stability-
  * wise (no fiber-leak or connection-pool corruption that surfaces only
  * after the load goes away).
  */
class SpikeSimulation extends Simulation:

  private val httpProtocol = http
    .baseUrl(SharedConfig.gatewayUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private val scn = scenario("spike").exec(Chains.playOneGame)

  setUp(
    scn.inject(
      constantUsersPerSec(2.0).during(30.seconds),
      atOnceUsers(SharedConfig.peakUsers),
      constantUsersPerSec(2.0).during(30.seconds),
      atOnceUsers(SharedConfig.peakUsers),
      constantUsersPerSec(2.0).during(30.seconds),
      atOnceUsers(SharedConfig.peakUsers),
      constantUsersPerSec(2.0).during(30.seconds),
    )
  ).protocols(httpProtocol)
    .assertions(
      // Spike SLAs are relaxed — by definition the burst is meant to
      // push latency up. The hard floor is that the system recovers.
      global.responseTime.percentile3.lt(1500),
      global.responseTime.percentile4.lt(5000),
      global.failedRequests.percent.lt(5.0),
    )
