package chess.gatling

import scala.concurrent.duration.*

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

/** Mixed-traffic test: 70% gameplay, 30% lobby flow, both running side
  * by side against the polyglot stack. Game flow goes through the
  * gateway; lobby flow goes straight to lobby-service. Each scenario
  * has its own HTTP protocol attached because the base URLs differ.
  *
  * Closer to real-world usage than running each smoke simulation alone:
  * surfaces cross-service contention (shared DB connection pool, shared
  * Kafka broker, gateway → game-service gRPC channel sharing) that
  * a single-endpoint scenario can't reproduce.
  */
class MixedSimulation extends Simulation:

  private val gatewayProtocol = http
    .baseUrl(SharedConfig.gatewayUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private val lobbyProtocol = http
    .baseUrl(SharedConfig.lobbyUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  // `import scala.concurrent.duration.*` brings DurationConversions into
  // scope and shadows Int.max via the conversion path — fall back to
  // `math.max` to keep the result an unambiguous Int.
  private val gameUsers: Int  = math.max(1, (SharedConfig.users * 7) / 10)
  private val lobbyUsers: Int = math.max(1, (SharedConfig.users * 3) / 10)

  private val gameScn  = scenario("mixed-game").exec(Chains.playOneGame)
  private val lobbyScn = scenario("mixed-lobby").exec(Chains.createAndJoinLobby)

  setUp(
    gameScn
      .inject(rampUsers(gameUsers).during(SharedConfig.rampSeconds.seconds))
      .protocols(gatewayProtocol),
    lobbyScn
      .inject(rampUsers(lobbyUsers).during(SharedConfig.rampSeconds.seconds))
      .protocols(lobbyProtocol),
  ).assertions(
    global.responseTime.percentile3.lt(750),
    global.responseTime.percentile4.lt(2500),
    global.failedRequests.percent.lt(1.0),
  )
