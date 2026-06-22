package chess.controller

import zio.*
import zio.http.*
import zio.stream.ZStream

/** SSE surface for the live analytics panel. Kept as its own `Routes` (combined
  * with `WebController.routes` via `++` in `GatewayMain`) so the main controller
  * and its route spec are untouched.
  *
  * `GET /api/analytics/events` streams every `chess.analytics` message the
  * [[AnalyticsRelay]] fans into `hub`, as `game-summary` SSE events. Each
  * connected browser subscribes independently to the hub.
  */
object AnalyticsRoutes:

  def routes(hub: Hub[String]): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "analytics" / "events" -> handler {
        val events =
          ZStream
            .fromHub(hub)
            .map(json => ServerSentEvent(data = json, eventType = Some("game-summary")))
        Response.fromServerSentEvents(events)
      }
    )
