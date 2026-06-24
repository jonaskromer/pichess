package chess.analytics

import zio.*
import zio.test.*

import chess.api.AnalyticsSummaryDto

object AnalyticsServiceSpec extends ZIOSpecDefault:

  /** Hand-rolled fake for the accessor delegation tests. */
  private final class FakeAnalyticsService(
      moves: List[(String, Long)],
      avgPlies: Option[Double],
      games: Long
  ) extends AnalyticsService:
    def record(summary: AnalyticsSummaryDto): UIO[Unit] = ZIO.unit
    def topMoves(limit: Int): Task[List[(String, Long)]] =
      ZIO.succeed(moves.take(limit))
    def averageGameLength: Task[Option[Double]] = ZIO.succeed(avgPlies)
    def gameCount: Task[Long]                   = ZIO.succeed(games)

  private val fake = new FakeAnalyticsService(
    moves    = List("e4" -> 10L, "d4" -> 7L, "c4" -> 3L),
    avgPlies = Some(42.5),
    games    = 99L
  )

  private def summary(id: String, opening: String, moves: Int): AnalyticsSummaryDto =
    AnalyticsSummaryDto(id, moves, 0L, opening, "GameEnded", "Draw", 0.0)

  def spec = suite("AnalyticsService")(
    suite("accessors delegate to the service")(
      test("topMoves") {
        for got <- AnalyticsService.topMoves(2)
        yield assertTrue(got == List("e4" -> 10L, "d4" -> 7L))
      },
      test("averageGameLength") {
        for got <- AnalyticsService.averageGameLength
        yield assertTrue(got.contains(42.5))
      },
      test("gameCount") {
        for got <- AnalyticsService.gameCount
        yield assertTrue(got == 99L)
      }
    ).provideLayer(ZLayer.succeed(fake)),
    suite("in-memory LiveAnalyticsService")(
      test("empty before any record") {
        for
          games <- AnalyticsService.gameCount
          avg   <- AnalyticsService.averageGameLength
          top   <- AnalyticsService.topMoves(5)
        yield assertTrue(games == 0L, avg.isEmpty, top.isEmpty)
      },
      test("folds recorded summaries into aggregates") {
        for
          svc <- ZIO.service[AnalyticsService]
          _   <- svc.record(summary("g1", "e4 e5", 20))
          _   <- svc.record(summary("g2", "e4 e5", 30))
          _   <- svc.record(summary("g2", "e4 e5", 30)) // redelivery — deduped
          _   <- svc.record(summary("g3", "d4 d5", 10))
          games <- svc.gameCount
          avg   <- svc.averageGameLength
          top   <- svc.topMoves(1)
        yield assertTrue(
          games == 3L,
          avg.contains(20.0),          // (20+30+10)/3, dup ignored
          top == List("e4 e5" -> 2L)   // most frequent opening
        )
      }
    ).provideLayer(LiveAnalyticsService.layer)
  )
