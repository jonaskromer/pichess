package chess.bot.tournament

import sttp.capabilities.zio.ZioStreams
import sttp.client3.UriContext
import sttp.client3.impl.zio.RIOMonadAsyncError
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode
import zio.*
import zio.json.*
import zio.stream.*
import zio.test.*

import chess.model.piece.Color

/** Exercises [[TournamentApiClient.sttp]] against an [[SttpBackendStub]]: URL
  * composition, the runtime-populated bearer token, NDJSON decoding, JSON
  * payload decoding, and error surfacing — all without a real server.
  */
object TournamentApiClientSpec extends ZIOSpecDefault:

  private val baseUri = uri"http://nowchess.local"
  private val config = TournamentApiClient.Config(baseUrl = baseUri)

  /** Fresh stub per test (the matcher chain is mutable). */
  private def stub: SttpBackendStub[Task, ZioStreams] =
    SttpBackendStub[Task, ZioStreams](new RIOMonadAsyncError[Any])

  private def ndjsonResponse(
      lines: List[String]
  ): ZStream[Any, Throwable, Byte] =
    ZStream.fromIterable(lines).intersperse("\n").via(ZPipeline.utf8Encode)

  private val registerBody = """{"id":"bot_x","token":"tok123"}"""

  def spec = suite("TournamentApiClient.sttp")(
    suite("register + auth")(
      test("register decodes the result and stores the token for later calls") {
        // After register, the bearer must be attached: the move matcher only
        // fires if Authorization == "Bearer tok123".
        val backend = stub
          .whenRequestMatches(_.uri.toString.endsWith("/api/auth/register"))
          .thenRespond(registerBody)
          .whenRequestMatches(r =>
            r.uri.toString.contains("/api/tournament/t1/game/g1/move/e2e4") &&
              r.headers.exists(h =>
                h.name == "Authorization" && h.value == "Bearer tok123"
              )
          )
          .thenRespondOk()
        for
          client <- TournamentApiClient.sttp(backend, config)
          reg <- client.register("piChess")
          moved <- client.makeMove("t1", "g1", "e2e4").exit
        yield assertTrue(
          reg == TournamentApiClient.RegisterResult("bot_x", "tok123"),
          moved.isSuccess
        )
      },
      test("register sends isBot:true with the given name") {
        val backend = stub
          .whenRequestMatches(r =>
            r.uri.toString.endsWith("/api/auth/register") &&
              r.body.show.contains("\"isBot\":true") &&
              r.body.show.contains("\"name\":\"piChess\"")
          )
          .thenRespond(registerBody)
        for
          client <- TournamentApiClient.sttp(backend, config)
          reg <- client.register("piChess").exit
        yield assertTrue(reg.isSuccess)
      },
      test("register on a malformed success body fails") {
        val backend = stub.whenAnyRequest.thenRespond("not json")
        for
          client <- TournamentApiClient.sttp(backend, config)
          res <- client.register("piChess").exit
        yield assertTrue(res.isFailure)
      },
      test("register non-2xx surfaces as TournamentApiError") {
        val backend =
          stub.whenAnyRequest.thenRespondWithCode(StatusCode.BadRequest, "bad")
        for
          client <- TournamentApiClient.sttp(backend, config)
          res <- client.register("piChess").exit
        yield assertTrue(res.causeOption.exists(_.failureOption.exists {
          case e: TournamentApiError => e.status == StatusCode.BadRequest
          case _                     => false
        }))
      }
    ),
    suite("postExpectOk endpoints")(
      test("joinTournament POSTs to the join URL on 200") {
        val backend = stub
          .whenRequestMatches(
            _.uri.toString == s"$baseUri/api/tournament/t1/join"
          )
          .thenRespondOk()
        for
          client <- TournamentApiClient.sttp(backend, config)
          res <- client.joinTournament("t1").exit
        yield assertTrue(res.isSuccess)
      },
      test(
        "a non-2xx join surfaces as TournamentApiError (e.g. 409 already started)"
      ) {
        val backend = stub.whenAnyRequest
          .thenRespondWithCode(StatusCode.Conflict, "already started")
        for
          client <- TournamentApiClient.sttp(backend, config)
          res <- client.joinTournament("t1").exit
        yield assertTrue(res.causeOption.exists(_.failureOption.exists {
          case e: TournamentApiError => e.status == StatusCode.Conflict
          case _                     => false
        }))
      }
    ),
    suite("getJson endpoints")(
      test(
        "getTournament decodes the (large) tournament object down to id + clock"
      ) {
        val big =
          """{"id":"t1","fullName":"Bots","clock":{"limit":300,"increment":3},
             |"variant":{"key":"standard"},"rated":true,"status":"created","round":0}""".stripMargin
        val backend = stub
          .whenRequestMatches(_.uri.toString == s"$baseUri/api/tournament/t1")
          .thenRespond(big)
        for
          client <- TournamentApiClient.sttp(backend, config)
          info <- client.getTournament("t1")
        yield assertTrue(
          info.id == "t1",
          info.clock.increment == 3,
          info.clock.limit == 300
        )
      },
      test("getTournament non-2xx fails") {
        val backend =
          stub.whenAnyRequest.thenRespondWithCode(StatusCode.NotFound, "nope")
        for
          client <- TournamentApiClient.sttp(backend, config)
          res <- client.getTournament("t1").exit
        yield assertTrue(res.isFailure)
      },
      test("getTournament on a malformed success body fails to decode") {
        val backend = stub.whenAnyRequest.thenRespond("{ not valid json")
        for
          client <- TournamentApiClient.sttp(backend, config)
          res <- client.getTournament("t1").exit
        yield assertTrue(res.isFailure)
      },
      test("listTournaments returns the created (joinable) tournaments") {
        val body =
          """{"created":[{"id":"t1","fullName":"Open Cup","clock":{"limit":60,"increment":1}}],"started":[],"finished":[]}"""
        val backend = stub
          .whenRequestMatches(_.uri.toString == s"$baseUri/api/tournament")
          .thenRespond(body)
        for
          client <- TournamentApiClient.sttp(backend, config)
          list <- client.listTournaments
        yield assertTrue(list.map(_.id) == List("t1"))
      },
      test("getGame projects the game JSON down to its two players") {
        // The flattened game object GET /api/tournament/{id}/game/{gameId} returns.
        val gameJson =
          """{"id":"g1","tournamentId":"t1","round":1,
             |"white":{"id":"bot_x","name":"piChess"},"black":{"id":"bot_y","name":"Rival"},
             |"moves":"e2e4","fen":"...","status":"ongoing","turn":"black","winner":null,
             |"clock":{"whiteTime":60.0,"blackTime":59.0},"startPosition":"standard"}""".stripMargin
        val backend = stub
          .whenRequestMatches(
            _.uri.toString == s"$baseUri/api/tournament/t1/game/g1"
          )
          .thenRespond(gameJson)
        for
          client <- TournamentApiClient.sttp(backend, config)
          game <- client.getGame("t1", "g1")
        yield assertTrue(
          game.white == BotRef("bot_x", "piChess"),
          game.black == BotRef("bot_y", "Rival")
        )
      }
    ),
    suite("NDJSON streams")(
      test("streamTournament parses the event stream") {
        val events: List[TournamentEvent] = List(
          TournamentEvent.TournamentStarted,
          TournamentEvent.GameStart(1, "g1", Color.White)
        )
        val backend =
          stub.whenAnyRequest.thenRespond(ndjsonResponse(events.map(_.toJson)))
        for
          client <- TournamentApiClient.sttp(backend, config)
          got <- client.streamTournament("t1").runCollect
        yield assertTrue(got.toList == events)
      },
      test("streamGame parses the game stream and filters blank lines") {
        val snapshot: GameEvent =
          GameEvent.StateSnapshot(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "",
            Color.White,
            GameClock(60.0, 60.0),
            "ongoing",
            None
          )
        val backend = stub.whenAnyRequest.thenRespond(
          ndjsonResponse(List("", snapshot.toJson, ""))
        )
        for
          client <- TournamentApiClient.sttp(backend, config)
          got <- client.streamGame("t1", "g1").runCollect
        yield assertTrue(got.toList == List(snapshot))
      },
      test("a malformed NDJSON line fails the stream") {
        val backend =
          stub.whenAnyRequest.thenRespond(ndjsonResponse(List("not json")))
        for
          client <- TournamentApiClient.sttp(backend, config)
          res <- client.streamGame("t1", "g1").runCollect.exit
        yield assertTrue(res.isFailure)
      }
    ),
    suite("Config")(
      test("default baseUrl is the production NowChess endpoint") {
        assertTrue(
          TournamentApiClient
            .Config()
            .baseUrl
            .toString == "https://nowchess.janis-eccarius.de"
        )
      }
    )
  )
