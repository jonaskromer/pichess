package chess.gatling

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

import scala.concurrent.duration.*

/** Endurance / soak test: constant open-loop arrival rate sustained for
  * `holdSeconds`. Surfaces issues that only appear over time —
  * memory leaks, GC heap growth, Kafka consumer-lag drift,
  * connection-pool fragmentation.
  *
  * Defaults are tuned for a 1-minute smoke. Raise `holdSeconds` to soak
  * the system for longer:
  *   sbt -DpichessRatePerSec=20 -DpichessHoldSeconds=1800 \
  *       'gatling/Gatling/testOnly chess.gatling.EnduranceSimulation'
  */
class EnduranceSimulation extends Simulation:

  private val httpProtocol = http
    .baseUrl(SharedConfig.gatewayUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private val scn = scenario("endurance").exec(Chains.playOneGame)

  setUp(
    scn.inject(
      constantUsersPerSec(SharedConfig.ratePerSec.toDouble)
        .during(SharedConfig.holdSeconds.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile3.lt(500),
      global.responseTime.percentile4.lt(2000),
      global.failedRequests.percent.lt(1.0),
    )
