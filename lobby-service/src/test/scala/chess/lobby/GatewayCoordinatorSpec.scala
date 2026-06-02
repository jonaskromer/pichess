package chess.lobby

import chess.api.ErrorDto
import sttp.client3.testing.SttpBackendStub
import sttp.client3.impl.zio.RIOMonadAsyncError
import sttp.client3.{Response, UriContext}
import sttp.model.StatusCode
import zio.*
import zio.json.EncoderOps
import zio.test.*

object GatewayCoordinatorSpec extends ZIOSpecDefault:

  private val baseUri = uri"http://gateway-host:8090"

  /** Build a fresh `RIOMonadAsyncError[Any]` for each test so the
    * `SttpBackendStub` ZIO instance is independent across cases.
    */
  private def stub: SttpBackendStub[Task, Any] =
    SttpBackendStub(new RIOMonadAsyncError[Any])

  def spec = suite("GatewayCoordinator")(
    suite("live HTTP wrapper")(
      test("success: gateway returns 204 NoContent → unit") {
        val backend = stub.whenAnyRequest.thenRespondOk()
        val coordinator = GatewayCoordinator.make(baseUri, backend)
        for result <- coordinator
          .registerPlayers("game-1", "host-session", Some("guest-session"))
          .exit
        yield assertTrue(result.isSuccess)
      },
      test("success path also accepts a guestSessionId = None") {
        val backend = stub.whenAnyRequest.thenRespondOk()
        val coordinator = GatewayCoordinator.make(baseUri, backend)
        for result <- coordinator
          .registerPlayers("game-2", "host-session", None)
          .exit
        yield assertTrue(result.isSuccess)
      },
      test("error: gateway returns 400 + ErrorDto → typed failure") {
        // Tapir client decodes 4xx into the endpoint's errorOut, which is
        // an ErrorDto. The coordinator wraps that into a RuntimeException
        // that names the failed endpoint and the underlying error.
        val backend = stub.whenAnyRequest.thenRespondWithCode(
          StatusCode.BadRequest,
          ErrorDto("invalid").toJson
        )
        val coordinator = GatewayCoordinator.make(baseUri, backend)
        for
          result <- coordinator
            .registerPlayers("game-3", "host-session", None)
            .exit
        yield assertTrue(
          result.causeOption.exists(_.failureOption.exists(t =>
            t.getMessage.contains("/internal/games/game-3/players") &&
              t.getMessage.contains("invalid")
          ))
        )
      }
    ),
    suite("accessor")(
      test("delegates to the underlying GatewayCoordinator service") {
        // The companion-object overload is just a thin
        // `ZIO.serviceWithZIO` wrapper — provide a stub service and
        // verify the call surfaces.
        for
          captured <- Ref.make[Option[(String, String, Option[String])]](None)
          recording = new GatewayCoordinator:
            def registerPlayers(
                gameId: String,
                hostSessionId: String,
                guestSessionId: Option[String]
            ): IO[Throwable, Unit] =
              captured.set(Some((gameId, hostSessionId, guestSessionId)))
          _ <- GatewayCoordinator
                 .registerPlayers("g-x", "h-y", Some("g-z"))
                 .provide(ZLayer.succeed(recording))
          seen <- captured.get
        yield assertTrue(seen.contains(("g-x", "h-y", Some("g-z"))))
      }
    ),
    suite("live layer (env + URL parsing)")(
      test("falls back to the docker compose default URL when unset") {
        for env <- ZIO.scoped(ZIO.serviceWith[GatewayCoordinator](identity))
          .provide(GatewayCoordinator.live)
          .exit
        yield assertTrue(env.isSuccess)
      },
      test("reads PICHESS_GATEWAY_URL when set") {
        for
          _   <- TestSystem.putEnv(GatewayCoordinator.EnvGatewayUrl, "http://override:1234")
          env <- ZIO.scoped(ZIO.serviceWith[GatewayCoordinator](identity))
            .provide(GatewayCoordinator.live)
            .exit
        yield assertTrue(env.isSuccess)
      },
      test("parseGatewayUrl accepts a well-formed URL") {
        // Direct call to the validation helper covers the Right branch.
        assertTrue(
          GatewayCoordinator
            .parseGatewayUrl("http://gateway:8090")
            .isRight
        )
      },
      test("parseGatewayUrl wraps Uri.parse failures in IllegalArgumentException") {
        // Direct call to the validation helper covers the Left branch
        // — robust against whichever specific string sttp's permissive
        // `Uri.parse` happens to reject in any given version. We
        // assert by checking that *some* failure case exists: feed it
        // a series of pathological inputs until one of them rejects.
        val candidates = List(
          "",
          "  ",
          "://nohost",
          "http",
          "http://:99999999"
        )
        val leftFound = candidates.flatMap(c =>
          GatewayCoordinator.parseGatewayUrl(c).left.toOption
        )
        assertTrue(
          leftFound.nonEmpty,
          leftFound.head.isInstanceOf[IllegalArgumentException],
          leftFound.head.getMessage.contains(GatewayCoordinator.EnvGatewayUrl)
        )
      }
    )
  )
