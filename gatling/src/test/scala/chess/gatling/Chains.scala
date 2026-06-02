package chess.gatling

import io.gatling.core.Predef.*
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef.*

/** Reusable scenario fragments shared across simulations. Each fragment is
  * a [[ChainBuilder]] so it can be plugged into any injection profile —
  * ramp, constant-rate, spike, mixed — without duplicating the request
  * sequence.
  */
object Chains:

  /** Reset to a fresh game, play the canonical 8-ply opening from
    * [[SharedConfig.openingMoves]], read the resulting state. Hits the
    * gateway, so it transparently exercises whichever `PICHESS_BACKEND`
    * the gameService + repository are running with.
    */
  val playOneGame: ChainBuilder =
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

  /** Create a lobby as host, join it as guest using the returned invite
    * code, read the lobby back. Hits lobby-service directly on its
    * dedicated port.
    */
  val createAndJoinLobby: ChainBuilder =
    exec(
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
