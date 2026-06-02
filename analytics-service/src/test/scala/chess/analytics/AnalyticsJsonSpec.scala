package chess.analytics

import chess.analytics.AnalyticsJson.*
import zio.json.*
import zio.test.*

object AnalyticsJsonSpec extends ZIOSpecDefault:

  def spec = suite("AnalyticsJson")(
    test("TopMove round-trips") {
      val v = TopMove(san = "e4", plays = 42L)
      assertTrue(v.toJson.fromJson[TopMove] == Right(v))
    },
    test("TopMovesResponse round-trips") {
      val v = TopMovesResponse(moves = List(TopMove("e4", 10L), TopMove("d4", 7L)))
      assertTrue(v.toJson.fromJson[TopMovesResponse] == Right(v))
    },
    test("AverageGameLengthResponse round-trips with a value") {
      val v = AverageGameLengthResponse(plies = Some(38.5))
      assertTrue(v.toJson.fromJson[AverageGameLengthResponse] == Right(v))
    },
    test("AverageGameLengthResponse round-trips when empty") {
      val v = AverageGameLengthResponse(plies = None)
      assertTrue(v.toJson.fromJson[AverageGameLengthResponse] == Right(v))
    },
    test("GameCountResponse round-trips") {
      val v = GameCountResponse(games = 123L)
      assertTrue(v.toJson.fromJson[GameCountResponse] == Right(v))
    }
  )
