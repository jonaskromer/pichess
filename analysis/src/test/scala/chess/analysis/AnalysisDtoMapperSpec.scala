package chess.analysis

import zio.test.*

import chess.opening.Opening

object AnalysisDtoMapperSpec extends ZIOSpecDefault:

  def spec = suite("AnalysisDtoMapper")(
    test("maps opening + move classes to NAG glyphs (Some/None)") {
      val analysis = GameAnalysis(
        opening = Opening(Some("B90"), "Sicilian Defense: Najdorf", "Sicilian", 10),
        moves = List(
          MoveAnalysis(0, "white", "e4", 20, 53.0, 0, 100.0, MoveClass.Best, "e4", List("e2e4")),
          MoveAnalysis(7, "black", "Qa5", -200, 30.0, 210, 40.0, MoveClass.Blunder, "Nf6", Nil)
        ),
        accuracyWhite = 95.0,
        accuracyBlack = 80.0
      )
      val dto = AnalysisDtoMapper.toDto(analysis)
      assertTrue(
        dto.opening.eco == Some("B90"),
        dto.opening.family == "Sicilian",
        dto.moves(0).moveClass == "Best",
        dto.moves(0).glyph == None,
        dto.moves(1).moveClass == "Blunder",
        dto.moves(1).glyph == Some("??"),
        dto.accuracyWhite == 95.0
      )
    }
  )
