package chess.gatling

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

import scala.concurrent.duration.*

/** Stress scenario for the lobby path. Each user:
  *
  *   1. Creates a lobby (`POST /lobbies`) capturing the returned invite code
  *   2. Joins the same lobby with a guest nickname
  *      (`POST /lobbies/by-code/{code}/join`)
  *   3. Reads the lobby (`GET /lobbies/{id}`)
  *
  * Since the lobby service is on its own port (`:8092`), this simulation
  * targets `pichessLobbyUrl` rather than `pichessGatewayUrl`. Run alongside
  * `GameSimulation` to stress the full polyglot stack.
  */
class LobbySimulation extends Simulation:

  private val httpProtocol = http
    .baseUrl(SharedConfig.lobbyUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private val createAndJoin = exec(
    http("create lobby")
      .post("/lobbies")
      .body(StringBody("""{"hostNickname":"alice"}"""))
      .check(status.is(200))
      .check(jsonPath("$.id").saveAs("lobbyId"))
      .check(jsonPath("$.inviteCode").saveAs("inviteCode"))
  ).exec(
    http("join lobby")
      .post("/lobbies/by-code/#{inviteCode}/join")
      .body(StringBody("""{"guestNickname":"bob"}"""))
      .check(status.is(200))
  ).exec(
    http("get lobby")
      .get("/lobbies/#{lobbyId}")
      .check(status.is(200))
  )

  private val scn = scenario("lobby-loop").exec(createAndJoin)

  setUp(
    scn.inject(
      rampUsers(SharedConfig.users).during(SharedConfig.rampSeconds.seconds)
    )
  ).protocols(httpProtocol)
