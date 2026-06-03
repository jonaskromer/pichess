package chess.analytics

import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.ztapir.*
import zio.*
import zio.http.*

import chess.analytics.AnalyticsJson.{
  AverageGameLengthResponse,
  GameCountResponse,
  TopMove,
  TopMovesResponse
}

/** REST surface for the analytics microservice. Wires Tapir endpoints to
  * [[AnalyticsService]] queries and returns JSON-encoded aggregates.
  */
object AnalyticsServer:

  def routes(svc: AnalyticsService): Routes[Any, Response] =
    ZioHttpInterpreter().toHttp(
      List(
        AnalyticsEndpoints.topMoves.zServerLogic[Any] { limit =>
          svc
            .topMoves(limit)
            .mapBoth(
              err => s"Top moves query failed: ${err.getMessage}",
              rows => TopMovesResponse(rows.map(TopMove.apply.tupled))
            )
        },
        AnalyticsEndpoints.averageGameLength.zServerLogic[Any] { _ =>
          svc.averageGameLength
            .mapBoth(
              err => s"Average game length query failed: ${err.getMessage}",
              AverageGameLengthResponse(_)
            )
        },
        AnalyticsEndpoints.gameCount.zServerLogic[Any] { _ =>
          svc.gameCount
            .mapBoth(
              err => s"Game count query failed: ${err.getMessage}",
              GameCountResponse(_)
            )
        },
        AnalyticsEndpoints.healthcheck.zServerLogic[Any](_ =>
          ZIO.succeed("ok")
        )
      )
    )
