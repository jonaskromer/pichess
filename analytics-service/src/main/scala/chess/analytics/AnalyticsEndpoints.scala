package chess.analytics

import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*

import chess.analytics.AnalyticsJson.{AverageGameLengthResponse, GameCountResponse, TopMovesResponse}

/** Tapir endpoint shapes for the analytics REST surface. Kept in their own
  * file so the import of `sttp.tapir.*` doesn't collide with `zio.http.*`
  * in the routes module.
  */
object AnalyticsEndpoints:

  private val base = endpoint.in("analytics").errorOut(stringBody)

  val topMoves: Endpoint[Unit, Int, String, TopMovesResponse, Any] =
    base.get
      .in("openings" / "top")
      .in(query[Int]("limit").default(10))
      .out(jsonBody[TopMovesResponse])

  val averageGameLength
      : Endpoint[Unit, Unit, String, AverageGameLengthResponse, Any] =
    base.get
      .in("games" / "average-length")
      .out(jsonBody[AverageGameLengthResponse])

  val gameCount: Endpoint[Unit, Unit, String, GameCountResponse, Any] =
    base.get
      .in("games" / "count")
      .out(jsonBody[GameCountResponse])

  val healthcheck: Endpoint[Unit, Unit, Unit, String, Any] =
    endpoint.get.in("healthcheck").out(stringBody)
