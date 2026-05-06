package chess.controller

import chess.api.{
  BoardStateDto,
  Endpoints,
  ErrorDto,
  ExportResponse,
  StateResponse
}
import chess.api.Endpoints.QuitAck
import chess.codec.FenParserRegex
import chess.model.GameError
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
import zio.stream.SubscriptionRef

/** Tapir-backed REST surface plus raw zio-http routes (HTML, web-ui assets,
  * SSE). Every command endpoint is a thin shim over the gameService gRPC
  * client; the gateway holds **no** authoritative game state — only the
  * `activeGameId` ref tracking which game in the gameService cluster this
  * gateway process is currently focused on.
  *
  * `activeGameId` is a `SubscriptionRef[String]` so the SSE source can
  * re-subscribe to a new game's stream when `/api/new` or `/api/load` is
  * called.
  */
object WebController:

  def routes(
      client: ZioGameService.GameServiceClient,
      activeGameId: SubscriptionRef[String],
      shutdown: Promise[Nothing, Unit]
  ): Routes[Any, Response] =
    tapirRoutes(client, activeGameId, shutdown) ++
      rawRoutes(client, activeGameId, shutdown)

  // --------------------------------------------------------------------------
  // Tapir-backed JSON API
  // --------------------------------------------------------------------------

  private def tapirRoutes(
      client: ZioGameService.GameServiceClient,
      activeGameId: SubscriptionRef[String],
      shutdown: Promise[Nothing, Unit]
  ): Routes[Any, Response] =
    val swagger = SwaggerInterpreter()
      .fromEndpoints[Task](Endpoints.all, "pichess API", "0.1.0")
    ZioHttpInterpreter().toHttp(
      swagger ++ List(
        Endpoints.getState.zServerLogic[Any] {
          case None | Some("view") =>
            currentBoard(client, activeGameId).map(StateResponse.View(_))
          case Some(other) =>
            exportInFormat(client, activeGameId, other).map(StateResponse.Export(_))
        },
        Endpoints.postMove.zServerLogic[Any] { req =>
          for
            id    <- activeGameId.get
            reply <- client
                       .makeMove(MoveRequest(id, req.move))
                       .mapError(toErrorDto)
            dto   <- replyToDto(reply)
          yield dto
        },
        Endpoints.postUndo.zServerLogic[Any](_ =>
          callOnActive(client, activeGameId, c => g => c.undo(GameIdRequest(g)))
        ),
        Endpoints.postRedo.zServerLogic[Any](_ =>
          callOnActive(client, activeGameId, c => g => c.redo(GameIdRequest(g)))
        ),
        Endpoints.postDraw.zServerLogic[Any](_ =>
          callOnActive(client, activeGameId, c => g => c.claimDraw(GameIdRequest(g)))
        ),
        Endpoints.postForfeit.zServerLogic[Any](_ =>
          callOnActive(client, activeGameId, c => g => c.forfeit(GameIdRequest(g)))
        ),
        Endpoints.postNew.zServerLogic[Any](_ =>
          for
            reply <- client.newGame(NewGameRequest()).mapError(toErrorDto)
            _     <- activeGameId.set(reply.gameId)
            dto   <- replyToDto(reply)
          yield dto
        ),
        Endpoints.postQuit.zServerLogic[Any](_ =>
          shutdown.succeed(()).as(QuitAck(quit = true))
        ),
        Endpoints.postLoad.zServerLogic[Any] { req =>
          for
            reply <- client.loadGame(LoadGameRequest(req.raw)).mapError(toErrorDto)
            _     <- activeGameId.set(reply.gameId)
            dto   <- replyToDto(reply)
          yield dto
        },
        Endpoints.getExport.zServerLogic[Any](
          exportInFormat(client, activeGameId, _)
        )
      )
    )

  private def callOnActive(
      client: ZioGameService.GameServiceClient,
      activeGameId: SubscriptionRef[String],
      action: ZioGameService.GameServiceClient => String => IO[StatusException, StateReply]
  ): ZIO[Any, ErrorDto, BoardStateDto] =
    for
      id    <- activeGameId.get
      reply <- action(client)(id).mapError(toErrorDto)
      dto   <- replyToDto(reply)
    yield dto

  private def exportInFormat(
      client: ZioGameService.GameServiceClient,
      activeGameId: SubscriptionRef[String],
      format: String
  ): ZIO[Any, ErrorDto, ExportResponse] =
    for
      id    <- activeGameId.get
      reply <- client.exportGame(ExportRequest(id, format)).mapError(toErrorDto)
    yield ExportResponse(reply.format, reply.body)

  private def toErrorDto(err: StatusException): ErrorDto =
    val description = Option(err.getStatus.getDescription).getOrElse(err.getMessage)
    ErrorDto(description)

  private def currentBoard(
      client: ZioGameService.GameServiceClient,
      activeGameId: SubscriptionRef[String]
  ): ZIO[Any, ErrorDto, BoardStateDto] =
    for
      id    <- activeGameId.get
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
      client: ZioGameService.GameServiceClient,
      activeGameId: SubscriptionRef[String],
      shutdown: Promise[Nothing, Unit]
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "" -> handler(servePage()),
      Method.GET / "web" / trailing ->
        handler((rest: zio.http.Path, _: Request) => serveAsset(rest)),
      Method.GET / "api" / "events" -> handler(
        serveEvents(client, activeGameId, shutdown)
      )
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
      activeGameId: SubscriptionRef[String],
      shutdown: Promise[Nothing, Unit]
  ): ZIO[Any, Nothing, Response] =
    ZIO.succeed {
      val stateEvents = activeGameId.changes
        .flatMap(id =>
          client
            .subscribeGame(GameIdRequest(id))
            .catchAll(_ => zio.stream.ZStream.empty)
        )
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
      val quitEvent = zio.stream.ZStream
        .fromZIO(shutdown.await)
        .map(_ =>
          ServerSentEvent(
            data = "quit",
            eventType = Some("quit")
          )
        )
      Response.fromServerSentEvents(stateEvents.merge(quitEvent))
    }
