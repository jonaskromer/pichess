package chess.gatling

import scala.concurrent.duration.*

import io.gatling.core.Predef.*
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef.*

/** Reusable scenario fragments shared across simulations. Each fragment is
  * a [[ChainBuilder]] so it can be plugged into any injection profile —
  * ramp, constant-rate, spike, mixed — without duplicating the request
  * sequence.
  *
  * Routing matches the live Tapir surface in `api/chess.api.Endpoints`:
  *   - `POST /api/games`               — mint a new game, returns `{id, state}`
  *   - `POST /api/games/{id}/move`     — apply a move (coord or SAN)
  *   - `GET  /api/games/{id}/state`    — current snapshot
  *   - `POST /lobbies`                 — create lobby (gateway-proxied)
  *   - `POST /lobbies/by-code/{c}/join`— join by invite code
  *   - `GET  /lobbies/{id}`            — fetch lobby
  *
  * Both per-game and per-lobby calls require an `X-Session-Id` header
  * (game) or a `hostSessionId` / `guestSessionId` body field (lobby).
  * Each virtual user gets its own UUID, set in a leading session step.
  */
object Chains:

  /** Set `sessionId` to a fresh UUID for the current virtual user. Used
    * as the first step of every chain that needs an `X-Session-Id`. */
  private val withSessionId: ChainBuilder =
    exec(session =>
      session.set("sessionId", java.util.UUID.randomUUID().toString)
    )

  /** Mint a fresh game and play the canonical 8-ply opening from
    * [[SharedConfig.openingMoves]]. Each ply mirrors the web-ui's interaction
    * shape: a `GET /legal-moves?from=<sq>` (the user picking up the piece)
    * before the actual `POST /move`. The shared prefix for both the read-only
    * flow ([[playOneGame]]) and the play-to-terminal flow ([[playAndForfeit]]).
    * Hits the gateway, so it transparently exercises whichever `PICHESS_BACKEND`
    * the gameService + repository are running with.
    */
  private val newGameAndOpening: ChainBuilder =
    SharedConfig.openingMoves.foldLeft(
      withSessionId.exec(
        http("new game")
          .post("/api/games")
          .header("X-Session-Id", "#{sessionId}")
          .header("content-type", "application/json")
          .body(StringBody("{}"))
          .check(status.is(200))
          .check(jsonPath("$.id").saveAs("gameId"))
      )
    ) { (chain, move) =>
      val fromSq = move.take(2) // moves are "e2 e4" — source square is first 2 chars
      chain
        .exec(
          http(s"legal-moves from $fromSq")
            .get(s"/api/games/#{gameId}/legal-moves?from=$fromSq")
            .check(status.is(200))
        )
        .exec(
          http(s"move $move")
            .post("/api/games/#{gameId}/move")
            .header("X-Session-Id", "#{sessionId}")
            .header("content-type", "application/json")
            .body(StringBody(s"""{"move":"$move"}"""))
            .check(status.is(200))
        )
    }

  /** [[newGameAndOpening]] then read the resulting state, `/threats`,
    * `/attackers`, and export as FEN — exercising the annotation cache + export
    * endpoints the smoke `get state` chain wouldn't touch. The game is left
    * in-progress (no terminal event, so no archive/analytics write).
    */
  val playOneGame: ChainBuilder =
    newGameAndOpening.exec(
      http("get state")
        .get("/api/games/#{gameId}/state")
        .check(status.is(200))
    ).exec(
      http("threats")
        .get("/api/games/#{gameId}/threats")
        .check(status.is(200))
    ).exec(
      http("attackers of e4")
        .get("/api/games/#{gameId}/attackers?of=e4")
        .check(status.is(200))
    ).exec(
      http("export fen")
        .get("/api/games/#{gameId}/export/fen")
        .check(status.is(200))
    )

  /** [[newGameAndOpening]] then **forfeit** — the deterministic way to drive a
    * game to a terminal state. This is the flow the smoke/stress games never
    * reach: `Forfeited` flows over Kafka to the repository's `GameArchiver`
    * (which builds the PGN archive and writes it to the mongo/redis archive
    * store) and to analytics/spark for the completed-game summary. Use it to put
    * the persistence + analytics *write* path under sustained load.
    */
  val playAndForfeit: ChainBuilder =
    newGameAndOpening.exec(
      http("forfeit")
        .post("/api/games/#{gameId}/forfeit")
        .header("X-Session-Id", "#{sessionId}")
        .check(status.is(200))
    )

  /** Submit one finished game straight to the repository's archive endpoint
    * (`POST /archives`) then read it back (`GET /archives/{id}`). Bypasses the
    * gateway + Kafka to isolate **archive-store write+read latency**
    * (`MongoGameArchiveRepository` / `RedisGameArchiveRepository`). Each virtual
    * user submits a distinct `gameId`, so writes fan out across keys rather than
    * hammering one. Drive this against [[SharedConfig.repositoryUrl]].
    */
  val submitAndReadArchive: ChainBuilder =
    exec(session =>
      session.set("gameId", java.util.UUID.randomUUID().toString)
    ).exec(
      http("submit archive")
        .post("/archives")
        .header("content-type", "application/json")
        .body(StringBody(SharedConfig.archiveSubmissionBody))
        // POST /archives returns 204 No Content on success.
        .check(status.is(204))
    ).exec(
      http("read archive")
        .get("/archives/#{gameId}")
        .check(status.is(200))
    )

  /** Analyze one game via `POST /api/analyze`. Expects `pgn` (movetext, no
    * result token) from the caller's feeder; appends a unique nonce comment + a
    * `*` result so every request is a distinct cache key — a real engine compute
    * rather than a `CachedAnalysisService` hit. That CPU-bound worst case (≈2
    * searches/ply, single-threaded on the game-service) is what the bottleneck
    * hunt targets, so keep concurrency modest. Drive against the gateway.
    */
  val analyzeGame: ChainBuilder =
    exec(session =>
      session.set("nonce", java.util.UUID.randomUUID().toString)
    ).exec(
      http("analyze")
        .post("/api/analyze")
        .header("content-type", "application/json")
        .body(
          StringBody(
            s"""{"pgn":"#{pgn} {#{nonce}} *","depth":${SharedConfig.analyzeDepth}}"""
          )
        )
        .check(status.is(200))
    )

  /** Read the analytics service's three query endpoints (its Kafka-fed in-memory
    * read model). Cheap O(1) reads — validates they stay fast under query load
    * while the ingest pipeline runs. Drive against [[SharedConfig.analyticsUrl]].
    */
  val queryAnalytics: ChainBuilder =
    exec(
      http("top openings")
        .get("/analytics/openings/top?limit=10")
        .check(status.is(200))
    ).exec(
      http("average game length")
        .get("/analytics/games/average-length")
        .check(status.is(200))
    ).exec(
      http("game count")
        .get("/analytics/games/count")
        .check(status.is(200))
    )

  /** Spectate a NowChess tournament game through the gateway: `POST
    * /tournament/{id}/game/{gameId}/spectate` mints (or reuses, deduped) a mirror
    * in game-service, then the user holds the mirror's SSE stream open like a
    * real watcher. Expects `spectateGameId` from the caller's feeder; the
    * tournament id is fixed per run ([[SharedConfig.tournamentId]]).
    *
    * This is the spectate hot path: many users on the same game share ONE mirror
    * + follower (the gateway dedups) and fan out over SSE, while users on
    * different games spin up distinct followers that each poll the tournament
    * server every second and replay moves into game-service. `?role=spectator`
    * makes the gateway count them on the mirror's presence gauge.
    */
  val spectateTournamentGame: ChainBuilder =
    exec(
      http("start tournament spectate")
        .post(
          s"/tournament/${SharedConfig.tournamentId}/game/#{spectateGameId}/spectate"
        )
        .check(status.is(200))
        .check(jsonPath("$.mirrorId").saveAs("mirrorId"))
    ).exec(
      sse("watch mirror")
        .get("/api/games/#{mirrorId}/events?role=spectator")
    ).pause(SharedConfig.spectateHoldSeconds.seconds)
    // No explicit close: Gatling tears down the open SSE stream when the
    // virtual user's session ends — i.e. the watcher leaves after holding.

  /** Browse the spectate surfaces: the open-tournaments relay (`/tournament/list`
    * → proxied to the NowChess server) and the unified ongoing-games index
    * (`/spectate/games` → 3-source fan-out: native game-service + tournament +
    * Lichess, each with a 2 s timeout). The browser polls these to populate the
    * "watch a game" screen, so they take real traffic; the fan-out's timeout +
    * partial-failure handling is worth stressing.
    */
  val browseSpectateIndex: ChainBuilder =
    exec(
      http("tournament list")
        .get("/tournament/list")
        .check(status.in(200, 502))
    ).exec(
      http("spectate index")
        .get("/spectate/games")
        .check(status.is(200))
    )

  /** Create a lobby as host, join it as guest using the returned invite
    * code, read the lobby back. Hits the lobby surface via the gateway
    * (proxied through `LobbyProxy.routes`).
    */
  val createAndJoinLobby: ChainBuilder =
    withSessionId.exec(session =>
      session.set("guestSessionId", java.util.UUID.randomUUID().toString)
    ).exec(
      http("create lobby")
        .post("/lobbies")
        .header("content-type", "application/json")
        .body(StringBody(
          """{"hostNickname":"alice","hostSessionId":"#{sessionId}",""" +
          """"visibility":"Public","allowUndo":true,"allowSpectate":true,""" +
          """"spectatorLimit":10}"""
        ))
        .check(status.is(200))
        .check(jsonPath("$.id").saveAs("lobbyId"))
        .check(jsonPath("$.inviteCode").saveAs("inviteCode"))
    ).exec(
      http("join lobby")
        .post("/lobbies/by-code/#{inviteCode}/join")
        .header("content-type", "application/json")
        .body(StringBody(
          """{"guestNickname":"bob","guestSessionId":"#{guestSessionId}"}"""
        ))
        .check(status.is(200))
    ).exec(
      http("get lobby")
        .get("/lobbies/#{lobbyId}")
        .check(status.is(200))
    )
