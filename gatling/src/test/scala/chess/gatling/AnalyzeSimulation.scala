package chess.gatling

import scala.concurrent.duration.*

import io.gatling.core.Predef.*
import io.gatling.http.Predef.*

/** Load on the **post-game analysis** path (`POST /api/analyze` →
  * game-service `AnalyzeGame`). This is the CPU-heaviest endpoint in the system:
  * the resident engine runs ≈2 searches per ply, single-threaded, so concurrent
  * requests contend for cores and head-of-line block. Each request carries a
  * unique nonce so it's a genuine compute (cache-miss) — the worst case the
  * bottleneck hunt wants to expose.
  *
  * Closed-loop ramp (heavy per-request cost makes open-loop arrival rates
  * meaningless — they'd pile up unbounded). Each user runs a few analyses over a
  * rotating PGN pool of increasing length. Assertions are deliberately loose: the
  * goal is to *measure* the latency curve vs. concurrency + depth, not to hold an
  * SLA. Run it ISOLATED (don't co-schedule with other load — CPU contention
  * distorts the numbers), and tune cost with `-DpichessAnalyzeDepth`.
  *
  *   sbt -DpichessUsers=8 -DpichessRampSeconds=10 -DpichessAnalyzeDepth=8 \
  *       'gatling/Gatling/testOnly chess.gatling.AnalyzeSimulation'
  */
class AnalyzeSimulation extends Simulation:

  private val httpProtocol = http
    .baseUrl(SharedConfig.gatewayUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  private val pgnFeeder =
    SharedConfig.analysisPgns.map(pgn => Map("pgn" -> pgn)).toArray.circular

  private val scn = scenario("analyze")
    .repeat(3)(feed(pgnFeeder).exec(Chains.analyzeGame))

  setUp(
    scn.inject(
      rampUsers(SharedConfig.users).during(SharedConfig.rampSeconds.seconds)
    )
  ).protocols(httpProtocol)
    .assertions(
      // Analyze is measured in seconds, not ms — these are guardrails, not SLAs.
      global.responseTime.percentile3.lt(30000),
      global.responseTime.percentile4.lt(60000),
      global.failedRequests.percent.lt(5.0),
    )
