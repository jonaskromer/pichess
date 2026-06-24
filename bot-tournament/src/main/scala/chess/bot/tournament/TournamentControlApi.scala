package chess.bot.tournament

import zio.*
import zio.http.*
import zio.json.*

/** Cluster-internal HTTP control surface over a [[TournamentManager]].
  *
  * This is how the gateway (Phase 3) signals the bot which tournaments to
  * enter. It is **not** meant to be exposed on the public ingress — anyone who
  * can reach it can enter piChess into arbitrary tournaments — so the k8s
  * Service is ClusterIP-only.
  *
  * {{{
  *   GET    /health                   → "ok" (liveness/readiness probe)
  *   GET    /control/tournaments      → { "active": ["t1", ...] }
  *   POST   /control/tournaments/{id} → join + play (idempotent) → { "ok": true }
  *   DELETE /control/tournaments/{id} → stop following → 204
  * }}}
  */
object TournamentControlApi:

  final case class ActiveResponse(active: List[String])
  object ActiveResponse:
    given JsonEncoder[ActiveResponse] = DeriveJsonEncoder.gen[ActiveResponse]

  def routes(manager: TournamentManager): Routes[Any, Response] =
    Routes(
      Method.GET / "health" -> handler(Response.text("ok")),
      Method.GET / "control" / "tournaments" -> handler { (_: Request) =>
        manager.activeTournaments.map(active =>
          Response.json(ActiveResponse(active.toList.sorted).toJson)
        )
      },
      Method.POST / "control" / "tournaments" / string("id") -> handler {
        (id: String, _: Request) =>
          manager
            .join(id)
            .as(Response.json("""{"ok":true}"""))
            .catchAll(e =>
              ZIO.succeed(
                Response
                  .text(s"join $id failed: ${e.getMessage}")
                  .status(Status.BadGateway)
              )
            )
      },
      Method.DELETE / "control" / "tournaments" / string("id") -> handler {
        (id: String, _: Request) =>
          manager.leave(id).as(Response.status(Status.NoContent))
      }
    )
