package chess.api

import zio.json.*
import zio.test.*

object GameAnalysisDtoSpec extends ZIOSpecDefault:

  private val sample = GameAnalysisDto(
    opening = OpeningDto(Some("B90"), "Sicilian Defense: Najdorf", "Sicilian", 10),
    moves = List(
      MoveAnalysisDto(0, "white", "e4", 20, 53.0, 0, 100.0, "Book", None, "e4", List("e2e4")),
      MoveAnalysisDto(7, "black", "Qa5", -180, 30.0, 210, 41.2, "Blunder", Some("??"), "Nf6", List("g1f3", "b8c6"))
    ),
    accuracyWhite = 96.4,
    accuracyBlack = 88.1
  )

  def spec = suite("GameAnalysisDto")(
    test("round-trips through JSON (incl. Some/None options)") {
      val json = sample.toJson
      assertTrue(json.fromJson[GameAnalysisDto] == Right(sample))
    },
    test("AnalyzeRequestDto round-trips") {
      val req = AnalyzeRequestDto("1. e4 c5 *", 12)
      assertTrue(req.toJson.fromJson[AnalyzeRequestDto] == Right(req))
    }
  )
