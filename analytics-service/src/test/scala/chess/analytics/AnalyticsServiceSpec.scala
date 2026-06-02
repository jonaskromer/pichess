package chess.analytics

import zio.*
import zio.test.*

object AnalyticsServiceSpec extends ZIOSpecDefault:

  /** Hand-rolled fake — the trait is tiny and the Live impl is exercised
    * end-to-end against a real ClickHouse via docker-compose, so a mock
    * library would be overkill here.
    */
  private final class FakeAnalyticsService(
      moves: List[(String, Long)],
      avgPlies: Option[Double],
      games: Long
  ) extends AnalyticsService:
    def topMoves(limit: Int): Task[List[(String, Long)]] =
      ZIO.succeed(moves.take(limit))
    def averageGameLength: Task[Option[Double]] = ZIO.succeed(avgPlies)
    def gameCount: Task[Long]                   = ZIO.succeed(games)

  private val fake = new FakeAnalyticsService(
    moves    = List("e4" -> 10L, "d4" -> 7L, "c4" -> 3L),
    avgPlies = Some(42.5),
    games    = 99L
  )

  private val layer: ULayer[AnalyticsService] = ZLayer.succeed(fake)

  def spec = suite("AnalyticsService accessors")(
    test("topMoves delegates to the service") {
      for got <- AnalyticsService.topMoves(2)
      yield assertTrue(got == List("e4" -> 10L, "d4" -> 7L))
    },
    test("averageGameLength delegates to the service") {
      for got <- AnalyticsService.averageGameLength
      yield assertTrue(got.contains(42.5))
    },
    test("gameCount delegates to the service") {
      for got <- AnalyticsService.gameCount
      yield assertTrue(got == 99L)
    },
  ).provideLayer(layer)
