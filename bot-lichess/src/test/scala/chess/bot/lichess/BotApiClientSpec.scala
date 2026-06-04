package chess.bot.lichess

import sttp.capabilities.zio.ZioStreams
import sttp.client3.UriContext
import sttp.client3.impl.zio.RIOMonadAsyncError
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode
import zio.*
import zio.json.*
import zio.stream.*
import zio.test.*

/** Exercises [[BotApiClient.sttp]] against an [[SttpBackendStub]] so
  * the URL composition, auth header, NDJSON decoding, and error
  * surfacing are pinned without needing a real Lichess connection.
  */
object BotApiClientSpec extends ZIOSpecDefault:

  private val baseUri = uri"http://lichess.local"
  private val config  = BotApiClient.Config(token = "tok", baseUrl = baseUri)

  /** Fresh stub backend per test so each test's matchers are
    * independent — the SttpBackendStub's matcher chain is mutable. */
  private def stub: SttpBackendStub[Task, ZioStreams] =
    SttpBackendStub[Task, ZioStreams](new RIOMonadAsyncError[Any])

  private def ndjsonResponse(lines: List[String]): ZStream[Any, Throwable, Byte] =
    ZStream
      .fromIterable(lines)
      .intersperse("\n")
      .via(ZPipeline.utf8Encode)

  def spec = suite("BotApiClient.sttp")(
    suite("postExpectOk endpoints")(
      test("acceptChallenge POSTs to the right URL on 200") {
        val backend = stub
          .whenRequestMatches(r =>
            r.uri.toString == s"$baseUri/api/challenge/c1/accept" &&
            r.headers.exists(h => h.name == "Authorization" && h.value == "Bearer tok")
          )
          .thenRespondOk()
        val client = BotApiClient.sttp(backend, config)
        for result <- client.acceptChallenge("c1").exit
        yield assertTrue(result.isSuccess)
      },
      test("makeMove POSTs the UCI to the right URL on 200") {
        val backend = stub
          .whenRequestMatches(_.uri.toString.contains("/api/bot/game/g1/move/e2e4"))
          .thenRespondOk()
        val client = BotApiClient.sttp(backend, config)
        for result <- client.makeMove("g1", "e2e4").exit
        yield assertTrue(result.isSuccess)
      },
      test("resign POSTs to /resign on 200") {
        val backend = stub
          .whenRequestMatches(_.uri.toString.contains("/api/bot/game/g1/resign"))
          .thenRespondOk()
        val client = BotApiClient.sttp(backend, config)
        for result <- client.resign("g1").exit
        yield assertTrue(result.isSuccess)
      },
      test("non-2xx response surfaces as BotApiError with the status code") {
        val backend = stub.whenAnyRequest.thenRespondWithCode(
          StatusCode.BadRequest, "Bad request"
        )
        val client = BotApiClient.sttp(backend, config)
        for result <- client.makeMove("g1", "e2e4").exit
        yield assertTrue(
          result.causeOption.exists(_.failureOption.exists {
            case e: BotApiError => e.status == StatusCode.BadRequest
            case _              => false
          })
        )
      },
    ),
    suite("ndjsonStream — streamEvents")(
      test("parses an account-events NDJSON body into AccountEvents") {
        val challenge: AccountEvent = AccountEvent.Challenge(
          ChallengeInfo(
            id = "c1", rated = false,
            variant = VariantRef("standard"),
            speed = "blitz", timeControl = TimeControlRef("clock"),
            challenger = PlayerRef(Some("u"), Some("u")),
          )
        )
        val gameStart: AccountEvent = AccountEvent.GameStart(GameRef("g1"))
        val body = List(challenge.toJson, gameStart.toJson)
        val backend = stub
          .whenAnyRequest
          .thenRespond(ndjsonResponse(body))
        val client = BotApiClient.sttp(backend, config)
        for events <- client.streamEvents.runCollect
        yield assertTrue(events.toList == List(challenge, gameStart))
      },
      test("malformed NDJSON line fails the stream") {
        val backend = stub
          .whenAnyRequest
          .thenRespond(ndjsonResponse(List("not json")))
        val client = BotApiClient.sttp(backend, config)
        for result <- client.streamEvents.runCollect.exit
        yield assertTrue(result.isFailure)
      },
      test("empty lines are filtered") {
        val gameStart: AccountEvent = AccountEvent.GameStart(GameRef("g1"))
        val body = List("", gameStart.toJson, "")
        val backend = stub
          .whenAnyRequest
          .thenRespond(ndjsonResponse(body))
        val client = BotApiClient.sttp(backend, config)
        for events <- client.streamEvents.runCollect
        yield assertTrue(events.toList == List(gameStart))
      },
    ),
    suite("ndjsonStream — streamGame")(
      test("parses a per-game NDJSON body into GameEvents") {
        val gameFull: GameEvent = GameEvent.GameFull(
          id = "g1", initialFen = "startpos",
          white = PlayerRef(Some("w"), Some("w")),
          black = PlayerRef(Some("b"), Some("b")),
          state = GameStateUpdate("", 60000, 60000, 0, 0, "started"),
        )
        val backend = stub
          .whenAnyRequest
          .thenRespond(ndjsonResponse(List(gameFull.toJson)))
        val client = BotApiClient.sttp(backend, config)
        for events <- client.streamGame("g1").runCollect
        yield assertTrue(events.toList == List(gameFull))
      },
    ),
    suite("Config")(
      test("default baseUrl is the production Lichess endpoint") {
        val c = BotApiClient.Config(token = "x")
        assertTrue(c.baseUrl.toString == "https://lichess.org")
      },
    ),
  )
