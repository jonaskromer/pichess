package chess.gatling

import scala.concurrent.duration.*

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

/** Smoke scenario for the gameplay path. Each virtual user runs the
  * canonical [[Chains.playOneGame]] flow once. The whole scenario goes
  * through the gateway, so it transparently exercises whichever
  * `PICHESS_BACKEND` the gameService + repository are running with —
  * flip the env var, rerun, compare the HTML reports under
  * `gatling/target/gatling/`.
  *
  * Defaults to a small ramp (10 users over 5s) — light enough to use as a
  * smoke check. For load / stress / endurance shapes pick one of the
  * dedicated simulations.
  */
class GameSimulation extends Simulation:

  private val httpProtocol = http
    .baseUrl(SharedConfig.gatewayUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private val scn = scenario("game-loop").exec(Chains.playOneGame)

  setUp(
    scn.inject(
      rampUsers(SharedConfig.users).during(SharedConfig.rampSeconds.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(
      // percentile3 / percentile4 default to p95 / p99 in Gatling 3.x.
      global.responseTime.percentile3.lt(500),
      global.responseTime.percentile4.lt(2000),
      global.failedRequests.percent.lt(1.0),
    )
