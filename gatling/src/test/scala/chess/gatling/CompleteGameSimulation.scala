package chess.gatling

import scala.concurrent.duration.*

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

/** Sustained **completed-game** load: each virtual user plays the opening and
  * then forfeits, so every iteration emits a terminal `Forfeited` event. Unlike
  * [[GameSimulation]] / [[StressSimulation]] (which leave games in-progress),
  * this drives the write-heavy tail the others never reach:
  *
  *   - game-service → Kafka `chess.game-events` (`MoveMade`×8 + `Forfeited`)
  *   - repository `GameArchiver` → **mongo/redis archive write** (one upsert per
  *     finished game)
  *   - analytics-service + spark → completed-game summary on `chess.analytics`
  *
  * So it is the load that actually exercises the persistence + analytics write
  * path under the prod (mongo+redis) stack. Requires the event-driven services
  * to be up (`make stack-mongo EXTRA=analytics` brings Kafka + repository +
  * analytics); without them the gameplay calls still succeed but nothing
  * downstream is exercised.
  *
  * Shape mirrors [[StressSimulation]]: ramp to `peakUsers`, then sustain
  * `ratePerSec` open-loop arrivals for `holdSeconds` — i.e. a steady rate of
  * games *finishing*, which is what stresses the archiver.
  *
  *   sbt -DpichessPeakUsers=100 -DpichessRampSeconds=20 \
  *       -DpichessHoldSeconds=120 -DpichessRatePerSec=10 \
  *       'gatling/Gatling/testOnly chess.gatling.CompleteGameSimulation'
  */
class CompleteGameSimulation extends Simulation:

  private val httpProtocol = http
    .baseUrl(SharedConfig.gatewayUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private val scn = scenario("complete-game").exec(Chains.playAndForfeit)

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
