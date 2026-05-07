package chess.controller

import chess.api.{
  BoardStateDto,
  Endpoints,
  ErrorDto,
  ExportResponse,
  GameSnapshot,
  StateResponse
}
import chess.codec.FenParserRegex
import chess.model.piece.Color
import chess.view.{HtmlPage, WebBoardView}
import io.grpc.StatusException
import pichess.game_service.{
  ExportRequest,
  GameIdRequest,
  LoadGameRequest,
  MoveRequest,
  NewGameRequest,
  StateReply,
  ZioGameService
}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.*
import zio.*
import zio.http.*

/** Tapir-backed REST surface plus raw zio-http routes (HTML, web-ui assets,
  * SSE). Every command endpoint is a thin shim over the gameService gRPC
  * client; the gateway holds **no** authoritative game state.
  *
  * **Routing**: every per-game endpoint is `/api/games/{id}/...`. The
  * gateway is stateless — there's no global "active game" any more. The
  * client tracks its own current gameId (in URL hash on the web-ui, in a
  * Ref on the TUI). `POST /api/games` mints a new game and returns its
  * id alongside the initial state.
  */
object WebController:

  def routes(
      client: ZioGameService.GameServiceClient,
      registry: SessionRegistry,
      cache: AnnotationCache
  ): Routes[Any, Response] =
    tapirRoutes(client, registry, cache) ++ rawRoutes(client)

  // --------------------------------------------------------------------------
  // Tapir-backed JSON API
  // --------------------------------------------------------------------------

  private def tapirRoutes(
      client: ZioGameService.GameServiceClient,
      registry: SessionRegistry,
      cache: AnnotationCache
  ): Routes[Any, Response] =
    val swagger = SwaggerInterpreter()
      .fromEndpoints[Task](Endpoints.all, "pichess API", "0.1.0")
    ZioHttpInterpreter().toHttp(
      swagger ++ List(
        Endpoints.postCreateGame.zServerLogic[Any] { case (sessionId, req) =>
          val create = req.load match
            case None       => client.newGame(NewGameRequest())
            case Some(load) => client.loadGame(LoadGameRequest(load))
          for
            reply <- create.mapError(toErrorDto)
            // Local-game registration: the creator is the only player and
            // is allowed to move both colours. Lobby-created games will
            // overwrite this via `registerLobby` when the lobby starts
            // (Phase 2).
            _     <- registry.registerLocal(reply.gameId, sessionId)
            dto   <- replyToDto(reply)
          yield GameSnapshot(reply.gameId, dto)
        },
        Endpoints.getState.zServerLogic[Any] { case (id, format) =>
          format match
            case None | Some("view") =>
              currentBoard(client, id).map(StateResponse.View(_))
            case Some(other) =>
              exportInFormat(client, id, other).map(StateResponse.Export(_))
        },
        Endpoints.postMove.zServerLogic[Any] { case (id, sessionId, req) =>
          gated(registry, id, sessionId) {
            for
              reply <- client
                         .makeMove(MoveRequest(id, req.move))
                         .mapError(toErrorDto)
              _     <- cache.invalidate(id)
              dto   <- replyToDto(reply)
            yield dto
          }
        },
        Endpoints.postUndo.zServerLogic[Any] { case (id, sessionId) =>
          gated(registry, id, sessionId) {
            callOnGame(client, id, c => g => c.undo(GameIdRequest(g)))
              .tap(_ => cache.invalidate(id))
          }
        },
        Endpoints.postRedo.zServerLogic[Any] { case (id, sessionId) =>
          gated(registry, id, sessionId) {
            callOnGame(client, id, c => g => c.redo(GameIdRequest(g)))
              .tap(_ => cache.invalidate(id))
          }
        },
        Endpoints.postDraw.zServerLogic[Any] { case (id, sessionId) =>
          gated(registry, id, sessionId) {
            callOnGame(client, id, c => g => c.claimDraw(GameIdRequest(g)))
              .tap(_ => cache.invalidate(id))
          }
        },
        Endpoints.postForfeit.zServerLogic[Any] { case (id, sessionId) =>
          gated(registry, id, sessionId) {
            callOnGame(client, id, c => g => c.forfeit(GameIdRequest(g)))
              .tap(_ => cache.invalidate(id))
          }
        },
        Endpoints.getExport.zServerLogic[Any] { case (id, format) =>
          exportInFormat(client, id, format)
        }
      )
    )

  /** Wrap a mutation handler in a session-id check. Refuses with a
    * "Forbidden: ..." 400 when the session isn't a registered active
    * player on the game. Tapir's `errorOut` gives us 400; spec-wise
    * this is a 403 in spirit, but adding the second status code would
    * mean a `oneOf[ApiError]` refactor we don't need yet — the message
    * carries the intent.
    */
  private def gated[A](
      registry: SessionRegistry,
      gameId: String,
      sessionId: String
  )(action: ZIO[Any, ErrorDto, A]): ZIO[Any, ErrorDto, A] =
    registry.canMutate(gameId, sessionId).flatMap {
      case true  => action
      case false =>
        ZIO.fail(
          ErrorDto(
            s"Forbidden: session $sessionId is not an active player on game $gameId"
          )
        )
    }

  private def callOnGame(
      client: ZioGameService.GameServiceClient,
      id: String,
      action: ZioGameService.GameServiceClient => String => IO[StatusException, StateReply]
  ): ZIO[Any, ErrorDto, BoardStateDto] =
    for
      reply <- action(client)(id).mapError(toErrorDto)
      dto   <- replyToDto(reply)
    yield dto

  private def exportInFormat(
      client: ZioGameService.GameServiceClient,
      id: String,
      format: String
  ): ZIO[Any, ErrorDto, ExportResponse] =
    for
      reply <- client.exportGame(ExportRequest(id, format)).mapError(toErrorDto)
    yield ExportResponse(reply.format, reply.body)

  private def toErrorDto(err: StatusException): ErrorDto =
    val description = Option(err.getStatus.getDescription).getOrElse(err.getMessage)
    ErrorDto(description)

  private def currentBoard(
      client: ZioGameService.GameServiceClient,
      id: String
  ): ZIO[Any, ErrorDto, BoardStateDto] =
    for
      reply <- client.getState(GameIdRequest(id)).mapError(toErrorDto)
      dto   <- replyToDto(reply)
    yield dto

  private def replyToDto(reply: StateReply): ZIO[Any, ErrorDto, BoardStateDto] =
    FenParserRegex
      .parse(reply.fen)
      .mapError(err => ErrorDto(err.message))
      .map { state =>
        WebBoardView.toDto(
          state,
          reply.moveLog.toList.map(e => (parseColor(e.color), e.san)),
          Option.when(reply.error.nonEmpty)(reply.error)
        )
      }

  private def parseColor(s: String): Color = s match
    case "White" => Color.White
    case "Black" => Color.Black
    case _       => Color.White // gameService never emits anything else

  // --------------------------------------------------------------------------
  // Raw zio-http routes (HTML / JS / SSE)
  // --------------------------------------------------------------------------

  private def rawRoutes(
      client: ZioGameService.GameServiceClient
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "" -> handler(servePage()),
      Method.GET / "web" / trailing ->
        handler((rest: zio.http.Path, _: Request) => serveAsset(rest)),
      Method.GET / "api" / "games" / string("id") / "events" -> handler {
        (id: String, _: Request) => serveEvents(client, id)
      }
    )

  private def servePage(): ZIO[Any, Nothing, Response] =
    ZIO.succeed(
      Response(
        status = Status.Ok,
        headers = Headers(Header.ContentType(MediaType.text.html)),
        body = Body.fromString(HtmlPage.render)
      )
    )

  /** Serve any file from the `web/` resource directory. Path traversal (`..`),
    * absolute paths, and segments containing characters outside
    * `[A-Za-z0-9._-]` all return 404 without touching the classpath. The
    * extension allow-list controls Content-Type — an unknown extension is also
    * a 404 so we never serve mystery bytes.
    */
  private def serveAsset(rest: zio.http.Path): ZIO[Any, Nothing, Response] =
    val relative = rest.toString.stripPrefix("/")
    contentTypeFor(relative) match
      case Some(contentType) if isSafeAssetPath(relative) =>
        serveClasspathResource(s"web/$relative", contentType)
      case _ =>
        ZIO.succeed(Response(status = Status.NotFound))

  private def isSafeAssetPath(p: String): Boolean =
    p.nonEmpty && p
      .split('/')
      .forall(seg =>
        seg.nonEmpty &&
          seg != ".." &&
          seg.forall(c => c.isLetterOrDigit || c == '.' || c == '-' || c == '_')
      )

  private def contentTypeFor(path: String): Option[MediaType] =
    val lower = path.toLowerCase
    if lower.endsWith(".js") then Some(MediaType.application.`javascript`)
    else if lower.endsWith(".svg") then Some(MediaType.image.`svg+xml`)
    else if lower.endsWith(".css") then Some(MediaType.text.css)
    else if lower.endsWith(".png") then Some(MediaType.image.png)
    else None

  private def serveClasspathResource(
      path: String,
      contentType: MediaType
  ): ZIO[Any, Nothing, Response] =
    ZIO.succeed {
      val stream = getClass.getClassLoader.getResourceAsStream(path)
      if stream == null then Response(status = Status.NotFound)
      else
        val bytes =
          try stream.readAllBytes()
          finally stream.close()
        Response(
          status = Status.Ok,
          headers = Headers(Header.ContentType(contentType)),
          body = Body.fromArray(bytes)
        )
    }

  private def serveEvents(
      client: ZioGameService.GameServiceClient,
      id: String
  ): ZIO[Any, Nothing, Response] =
    ZIO.succeed {
      val stateEvents = client
        .subscribeGame(GameIdRequest(id))
        .catchAll(_ => zio.stream.ZStream.empty)
        .mapZIO(reply =>
          replyToDto(reply).either.map {
            case Right(dto) =>
              Some(
                ServerSentEvent(
                  data = zio.json.EncoderOps(dto).toJson,
                  eventType = Some("state")
                )
              )
            case Left(_) => None
          }
        )
        .collectSome
      Response.fromServerSentEvents(stateEvents)
    }
