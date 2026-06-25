package chess.repository.api

import sttp.model.Method
import zio.json.*
import zio.test.*

object RepositoryEndpointsSpec extends ZIOSpecDefault:

  def spec = suite("RepositoryEndpoints")(
    suite("GameStateEnvelope JSON codec")(
      test("round-trips through JSON") {
        val env = GameStateEnvelope(
          fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        )
        assertTrue(env.toJson.fromJson[GameStateEnvelope] == Right(env))
      },
      test("encodes the fen field by name") {
        val env = GameStateEnvelope(fen = "8/8/8/8/8/8/8/4k2K w - - 0 1")
        assertTrue(env.toJson.contains("\"fen\":\"8/8/8/8/8/8/8/4k2K w - - 0 1\""))
      }
    ),
    suite("LoadFailure ADT")(
      // The case object + final-case-class arms of the sealed trait are the
      // statement-coverage targets here. Distinct identities and pattern
      // matching also exercise the synthetic case-class machinery scoverage
      // would otherwise tag as uncovered.
      test("NotFound and ServerError are distinct values") {
        val notFound = LoadFailure.NotFound
        val serverErr = LoadFailure.ServerError("boom")
        assertTrue(
          notFound != serverErr,
          serverErr.message == "boom"
        )
      },
      test("pattern-matches both arms") {
        def describe(f: LoadFailure): String = f match
          case LoadFailure.NotFound          => "404"
          case LoadFailure.ServerError(msg)  => s"500: $msg"
        assertTrue(
          describe(LoadFailure.NotFound) == "404",
          describe(LoadFailure.ServerError("db")) == "500: db"
        )
      },
      test("serverError codec helpers round-trip") {
        // Exercises both sides of the named codec helpers used inside
        // `loadErrorOut`. Without these direct calls, scoverage flags
        // both lambdas as uncovered because the codec path is only
        // reachable end-to-end via the server interpreter.
        val err = LoadFailure.ServerError("boom")
        assertTrue(
          RepositoryCodecs.serverErrorToMessage(err) == "boom",
          RepositoryCodecs.serverErrorFromMessage("boom") == err
        )
      }
    ),
    suite("Tapir endpoints")(
      // `Endpoint#show` renders the full HTTP shape including the
      // method and the space-separated path segments, e.g.
      //   "[saveGame] PUT /games /[id] {body as application/json …} -> …"
      // — so we substring-match both `/games` and the `/[id]` segment
      // to confirm both the verb and the path shape.
      test("saveGame is a PUT under /games/{id}") {
        val ep = RepositoryEndpoints.saveGame
        val rendered = ep.show
        assertTrue(
          ep.method.contains(Method.PUT),
          rendered.contains("/games"),
          rendered.contains("/[id]")
        )
      },
      test("loadGame is a GET under /games/{id}") {
        val ep = RepositoryEndpoints.loadGame
        val rendered = ep.show
        assertTrue(
          ep.method.contains(Method.GET),
          rendered.contains("/games"),
          rendered.contains("/[id]")
        )
      },
      test("deleteGame is a DELETE under /games/{id}") {
        val ep = RepositoryEndpoints.deleteGame
        val rendered = ep.show
        assertTrue(
          ep.method.contains(Method.DELETE),
          rendered.contains("/games"),
          rendered.contains("/[id]")
        )
      },
      test("`all` exposes every endpoint exactly once") {
        // Sanity guard: someone adding a new endpoint MUST add it to
        // `all`, otherwise it falls out of any iteration-based wiring
        // (Swagger docs generator, server interpreter, etc.).
        val names = RepositoryEndpoints.all.map(_.showShort)
        assertTrue(
          RepositoryEndpoints.all.size == 5,
          names.distinct.size == 5
        )
      }
    )
  )
