package chess.gatling

import scala.concurrent.duration.*

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

/** Load on the **spectate-index** surfaces (`/tournament/list`,
  * `/spectate/games`) — the gateway's "find a game to watch" screen. Both fan out
  * to external/internal sources (the NowChess server, game-service, Lichess) with
  * per-source 2 s timeouts; under polling load this surfaces whether the fan-out
  * degrades (timeout pile-ups, slow tournament-pairings walks). Stress shape:
  * ramp to `peakUsers`, then sustain `ratePerSec` (mirrors browsers polling).
  *
  *   sbt -DpichessPeakUsers=100 -DpichessHoldSeconds=120 -DpichessRatePerSec=20 \
  *       'gatling/Gatling/testOnly chess.gatling.SpectateIndexSimulation'
  */
class SpectateIndexSimulation extends Simulation:

  private val httpProtocol = http
    .baseUrl(SharedConfig.gatewayUrl)
    .acceptHeader("application/json")

  private val scn = scenario("spectate-index").exec(Chains.browseSpectateIndex)

  setUp(
    scn.inject(
      rampUsers(SharedConfig.peakUsers)
        .during(SharedConfig.rampSeconds.seconds),
      constantUsersPerSec(SharedConfig.ratePerSec.toDouble)
        .during(SharedConfig.holdSeconds.seconds),
    )
  ).protocols(httpProtocol)
    .assertions(
      // /spectate/games is bounded by its own 2 s per-source timeout.
      global.responseTime.percentile3.lt(2500),
      global.failedRequests.percent.lt(5.0),
    )
