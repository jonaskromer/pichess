package chess.api

import zio.json.*
import zio.test.*

object AnalyticsSummaryDtoSpec extends ZIOSpecDefault:

  def spec = suite("AnalyticsSummaryDto")(
    test("round-trips through JSON") {
      val dto = AnalyticsSummaryDto(
        gameId = "g1",
        totalMoves = 3,
        durationMs = 4000L,
        opening = "e4 d5 exd5",
        result = "DrawClaimed",
        outcome = "Draw",
        avgThinkTimeMs = 2000.0
      )
      assertTrue(dto.toJson.fromJson[AnalyticsSummaryDto] == Right(dto))
    },
    test("decodes the Spark producer's payload shape") {
      val json =
        """{"gameId":"g2","totalMoves":2,"durationMs":3000,"opening":"e4 e5","result":"Forfeited","outcome":"White","avgThinkTimeMs":3000.0}"""
      assertTrue(
        json.fromJson[AnalyticsSummaryDto] ==
          Right(AnalyticsSummaryDto("g2", 2, 3000L, "e4 e5", "Forfeited", "White", 3000.0))
      )
    }
  )
