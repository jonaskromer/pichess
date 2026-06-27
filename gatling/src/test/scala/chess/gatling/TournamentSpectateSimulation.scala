package chess.gatling

import scala.concurrent.duration.*

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

/** Load on the **tournament spectate** path — the gateway feature that mirrors a
  * live NowChess tournament game onto piChess's own board. Each virtual user
  * `POST /tournament/{id}/game/{gameId}/spectate` (the gateway mints/reuses a
  * mirror game-service game + forks a follower that polls the real tournament
  * server) and then holds the mirror's SSE stream open like a real watcher.
  *
  * Two concurrency axes, both driven by the `.random` spread over the seeded
  * gameIds:
  *   - **fan-out**: many users land on the same game → ONE mirror + ONE follower,
  *     N SSE subscribers — stresses the gateway's SSE fan-out + presence counting
  *   - **breadth**: users spread across M games → M followers, each polling the
  *     tournament server every second + replaying moves into game-service
  *
  * Requires a seeded tournament: run `scripts/tournament-seed.sh` (stands up a
  * real tournament on ../tournament-server) and pass its output:
  *   sbt -DpichessTournamentId=<id> -DpichessSpectateGameIds=g1,g2,g3 \
  *       -DpichessPeakUsers=100 -DpichessRampSeconds=20 \
  *       'gatling/Gatling/testOnly chess.gatling.TournamentSpectateSimulation'
  * The gateway must have PICHESS_TOURNAMENT_URL pointed at that same server.
  */
class TournamentSpectateSimulation extends Simulation:

  private val httpProtocol = http
    .baseUrl(SharedConfig.gatewayUrl)

  // Fall back to a sentinel so the sim still *compiles + runs* (and fails
  // loudly per-request) if launched without seeding, rather than an empty-feeder
  // crash at setup.
  private val gameIds =
    if SharedConfig.spectateGameIds.nonEmpty then SharedConfig.spectateGameIds
    else List("UNSEEDED")

  private val gameFeeder =
    gameIds.map(g => Map("spectateGameId" -> g)).toArray.random

  private val scn = scenario("tournament-spectate")
    .feed(gameFeeder)
    .exec(Chains.spectateTournamentGame)

  setUp(
    scn.inject(
      rampUsers(SharedConfig.peakUsers).during(SharedConfig.rampSeconds.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(
      // The spectate POST should be quick; the SSE hold dominates wall-clock but
      // isn't a "response time" in the usual sense. Loose guardrails.
      global.responseTime.percentile3.lt(2000),
      global.failedRequests.percent.lt(5.0),
    )
