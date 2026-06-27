package chess.gatling

import scala.concurrent.duration.*

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

/** Load on the **analytics read model** (`GET /analytics/openings/top`,
  * `/games/average-length`, `/games/count`) — the Kafka-fed in-memory
  * projection the analytics-service serves over HTTP. These are O(1) reads off a
  * `Ref[AnalyticsState]`, so they should stay flat-fast even while the Kafka
  * ingest pipeline folds completed games behind them; this simulation verifies
  * that (run it alongside [[CompleteGameSimulation]] to have real ingest).
  *
  * Targets the analytics service directly (`pichessAnalyticsUrl`, default :8093),
  * not the gateway. Stress shape: ramp to `peakUsers`, then sustain `ratePerSec`.
  *
  *   sbt -DpichessAnalyticsUrl=http://localhost:8093 \
  *       -DpichessPeakUsers=100 -DpichessHoldSeconds=120 -DpichessRatePerSec=50 \
  *       'gatling/Gatling/testOnly chess.gatling.AnalyticsQuerySimulation'
  */
class AnalyticsQuerySimulation extends Simulation:

  private val httpProtocol = http
    .baseUrl(SharedConfig.analyticsUrl)
    .acceptHeader("application/json")

  private val scn = scenario("analytics-query").exec(Chains.queryAnalytics)

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
