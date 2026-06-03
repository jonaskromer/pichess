package chess.gatling

import scala.concurrent.duration.*

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

/** Stress test: ramp to `peakUsers` over `rampSeconds`, then sustain
  * `ratePerSec` open-loop arrivals for `holdSeconds`. The goal is to
  * surface sustained-load failures (connection-pool exhaustion,
  * back-pressure breaking down, GC pause-time creep) that don't show up
  * under the smoke `GameSimulation`.
  *
  * Suggested invocation:
  *   sbt -DpichessPeakUsers=200 -DpichessRampSeconds=30 \
  *       -DpichessHoldSeconds=120 -DpichessRatePerSec=10 \
  *       'gatling/Gatling/testOnly chess.gatling.StressSimulation'
  *
  * Assertions are loosened compared to the smoke simulations — under
  * stress, breaching the smoke SLAs is the point.
  */
class StressSimulation extends Simulation:

  private val httpProtocol = http
    .baseUrl(SharedConfig.gatewayUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private val scn = scenario("stress").exec(Chains.playOneGame)

  setUp(
    scn.inject(
      rampUsers(SharedConfig.peakUsers)
        .during(SharedConfig.rampSeconds.seconds),
      constantUsersPerSec(SharedConfig.ratePerSec.toDouble)
        .during(SharedConfig.holdSeconds.seconds),
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile3.lt(1000),
      global.responseTime.percentile4.lt(3000),
      global.failedRequests.percent.lt(5.0),
    )
