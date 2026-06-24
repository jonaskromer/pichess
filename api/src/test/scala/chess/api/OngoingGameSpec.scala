package chess.api

import zio.json.*
import zio.test.*

object OngoingGameSpec extends ZIOSpecDefault:

  def spec = suite("OngoingGame")(
    test("round-trips a native (vs-bot) game through JSON") {
      val g = OngoingGame(
        id = "g1",
        gameType = "pvbot",
        white = "piChess (bot)",
        black = "Player",
        status = "ongoing",
        spectators = 2,
        limit = 8,
        spectateable = true,
        tournamentId = None
      )
      assertTrue(g.toJson.fromJson[OngoingGame] == Right(g))
    },
    test("round-trips a tournament game (carries its tournamentId)") {
      val g = OngoingGame(
        id = "gx",
        gameType = "tournament",
        white = "Alice",
        black = "Bob",
        status = "ongoing",
        spectators = 0,
        limit = 0,
        spectateable = true,
        tournamentId = Some("t1")
      )
      assertTrue(g.toJson.fromJson[OngoingGame] == Right(g))
    }
  )
