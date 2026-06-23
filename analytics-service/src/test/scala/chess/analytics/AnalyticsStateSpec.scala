package chess.analytics

import zio.test.*

import chess.api.AnalyticsSummaryDto

object AnalyticsStateSpec extends ZIOSpecDefault:

  private def summary(opening: String, moves: Int): AnalyticsSummaryDto =
    AnalyticsSummaryDto("g", moves, 0L, opening, "GameEnded", 0.0)

  def spec = suite("AnalyticsState")(
    test("empty has no games and no average") {
      assertTrue(
        AnalyticsState.empty.games == 0L,
        AnalyticsState.empty.averagePlies.isEmpty,
        AnalyticsState.empty.topOpenings(3).isEmpty
      )
    },
    test("fold accumulates games, moves and opening counts") {
      val s = List(
        summary("e4 e5", 20),
        summary("e4 e5", 30),
        summary("d4 d5", 10)
      ).foldLeft(AnalyticsState.empty)(AnalyticsState.fold)
      assertTrue(
        s.games == 3L,
        s.totalMoves == 60L,
        s.averagePlies.contains(20.0),
        s.topOpenings(2) == List("e4 e5" -> 2L, "d4 d5" -> 1L)
      )
    },
    test("empty opening is bucketed under a placeholder") {
      val s = AnalyticsState.fold(AnalyticsState.empty, summary("", 0))
      assertTrue(s.topOpenings(1) == List("(no moves)" -> 1L))
    }
  )
