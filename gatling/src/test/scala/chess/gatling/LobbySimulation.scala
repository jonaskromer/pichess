package chess.gatling

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

import scala.concurrent.duration.*

/** Smoke scenario for the lobby path. Each user runs the canonical
  * [[Chains.createAndJoinLobby]] flow once. Targets `pichessLobbyUrl`
  * directly (lobby-service is on its own port `:8092`, distinct from the
  * gateway).
  *
  * Run alongside [[GameSimulation]] or use [[MixedSimulation]] to stress
  * the full polyglot stack.
  */
class LobbySimulation extends Simulation:

  private val httpProtocol = http
    .baseUrl(SharedConfig.lobbyUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private val scn = scenario("lobby-loop").exec(Chains.createAndJoinLobby)

  setUp(
    scn.inject(
      rampUsers(SharedConfig.users).during(SharedConfig.rampSeconds.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(
      global.responseTime.percentile3.lt(500),
      global.responseTime.percentile4.lt(2000),
      global.failedRequests.percent.lt(1.0),
    )
