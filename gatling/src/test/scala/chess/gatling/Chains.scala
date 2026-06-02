package chess.gatling

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

  /** Mint a fresh game, play the canonical 8-ply opening from
    * [[SharedConfig.openingMoves]], read the resulting state. Hits the
    * gateway, so it transparently exercises whichever `PICHESS_BACKEND`
    * the gameService + repository are running with.
    */
  val playOneGame: ChainBuilder =
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
      chain.exec(
        http(s"move $move")
          .post("/api/games/#{gameId}/move")
          .header("X-Session-Id", "#{sessionId}")
          .header("content-type", "application/json")
          .body(StringBody(s"""{"move":"$move"}"""))
          .check(status.is(200))
      )
    }.exec(
      http("get state")
        .get("/api/games/#{gameId}/state")
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
