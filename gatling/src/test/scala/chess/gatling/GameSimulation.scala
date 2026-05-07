package chess.gatling

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

import scala.concurrent.duration.*

/** Stress scenario for the gameplay path. Each virtual user:
  *
  *   1. Resets to a fresh game with `POST /api/new`
  *   2. Plays the canonical 8-ply opening from [[SharedConfig]]
  *   3. Reads the state once at the end via `GET /api/state`
  *
  * The whole scenario goes through the gateway, so it transparently
  * exercises whichever `PICHESS_BACKEND` the gameService + repository are
  * running with — flip the env var, rerun, compare the HTML reports under
  * `gatling/target/gatling/`.
  */
class GameSimulation extends Simulation:

  private val httpProtocol = http
    .baseUrl(SharedConfig.gatewayUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private val playOneGame =
    SharedConfig.openingMoves.foldLeft(
      exec(
        http("new game")
          .post("/api/new")
          .check(status.is(200))
      )
    ) { (chain, move) =>
      chain.exec(
        http(s"move $move")
          .post("/api/move")
          .body(StringBody(s"""{"move":"$move"}"""))
          .check(status.is(200))
      )
    }.exec(
      http("get state")
        .get("/api/state")
        .check(status.is(200))
    )

  private val scn = scenario("game-loop").exec(playOneGame)

  setUp(
    scn.inject(
      rampUsers(SharedConfig.users).during(SharedConfig.rampSeconds.seconds)
    )
  ).protocols(httpProtocol)
