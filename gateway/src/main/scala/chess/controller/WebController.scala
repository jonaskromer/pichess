package chess.controller

import chess.api.{BoardStateDto, Endpoints, ErrorDto, ExportResponse, LoadRequest, MoveRequest, StateResponse}
import chess.api.Endpoints.QuitAck
import chess.codec.{FenSerializer, JsonSerializer, PgnSerializer}
import chess.model.{GameError, GameSnapshot, SessionState}
import chess.notation.SanSerializer
import chess.service.GameService
import chess.view.{HtmlPage, WebBoardView}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.*
import zio.*
import zio.http.*
import zio.stream.SubscriptionRef

// JSON endpoints under /api are defined in the shared `Endpoints` module and
// interpreted here via ZioHttpInterpreter — so the Laminar UI and internal
// callers can share the same typed contract. HTML, the Scala.js bundle, and
// the SSE stream stay as raw zio-http routes because they don't fit Tapir's
// typed body model well.
object WebController:

  def routes(
      gs: GameService,
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit],
  ): Routes[Any, Response] =
    tapirRoutes(gs, session, shutdown) ++ rawRoutes(session, shutdown)

  // --------------------------------------------------------------------------
  // Tapir-backed JSON API
  // --------------------------------------------------------------------------

  private def tapirRoutes(
      gs: GameService,
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit],
  ): Routes[Any, Response] =
    val swagger = SwaggerInterpreter()
      .fromEndpoints[Task](Endpoints.all, "pichess API", "0.1.0")
    ZioHttpInterpreter().toHttp(
      swagger ++ List(
        Endpoints.getState.zServerLogic[Any] {
          case None | Some("view") =>
            currentBoard(session).map(StateResponse.View(_))
          case Some(other) =>
            exportInFormat(session, other).map(StateResponse.Export(_))
        },
        Endpoints.postMove.zServerLogic[Any] { req =>
          GameController
            .makeMove(gs, session, req.move)
            .mapError(toErrorDto)
            .zipRight(currentBoard(session))
        },
        Endpoints.postUndo.zServerLogic[Any](_ =>
          GameController
            .undo(gs, session)
            .mapError(toErrorDto)
            .zipRight(currentBoard(session))
        ),
        Endpoints.postRedo.zServerLogic[Any](_ =>
          GameController
            .redo(gs, session)
            .mapError(toErrorDto)
            .zipRight(currentBoard(session))
        ),
        Endpoints.postDraw.zServerLogic[Any](_ =>
          GameController
            .claimDraw(gs, session)
            .mapError(toErrorDto)
            .zipRight(currentBoard(session))
        ),
        Endpoints.postForfeit.zServerLogic[Any](_ =>
          GameController
            .forfeit(gs, session)
            .mapError(toErrorDto)
            .zipRight(currentBoard(session))
        ),
        Endpoints.postNew.zServerLogic[Any](_ =>
          gs.newGame()
            .mapError(err => ErrorDto(err.message))
            .flatMap(event =>
              session.set(
                SessionState(
                  GameSnapshot.fresh(event.gameId, event.initialState)
                )
              )
            )
            .zipRight(currentBoard(session))
        ),
        Endpoints.postQuit.zServerLogic[Any](_ =>
          shutdown.succeed(()).as(QuitAck(quit = true))
        ),
        Endpoints.postLoad.zServerLogic[Any] { req =>
          gs.loadGame(req.raw)
            .mapError(toErrorDto)
            .flatMap { case (event, history) =>
              session.set(
                SessionState(
                  GameSnapshot.fromHistory(
                    event.gameId,
                    event.initialState,
                    history.reverse,
                  )
                )
              )
            }
            .zipRight(currentBoard(session))
        },
        Endpoints.getExport.zServerLogic[Any](exportInFormat(session, _)),
      ),
    )

  private def exportInFormat(
      session: SubscriptionRef[SessionState],
      format: String,
  ): ZIO[Any, ErrorDto, ExportResponse] =
    val normalized = format.toLowerCase
    session.get.flatMap { s =>
      normalized match
        case "fen" =>
          ZIO.succeed(
            ExportResponse("fen", FenSerializer.serialize(s.state))
          )
        case "json" =>
          ZIO.succeed(
            ExportResponse("json", JsonSerializer.serialize(s.state))
          )
        case "pgn" =>
          SanSerializer
            .deriveMoveLog(s.initialState, s.history)
            .orDie
            .flatMap(log =>
              PgnSerializer.serialize(log, s.state.status)
            )
            .map(ExportResponse("pgn", _))
        case other =>
          ZIO.fail(
            ErrorDto(
              s"Unknown format '$other'; expected fen, pgn, or json"
            )
          )
    }

  private def toErrorDto(err: GameError): ErrorDto = ErrorDto(err.message)

  private def currentBoard(
      session: SubscriptionRef[SessionState]
  ): ZIO[Any, ErrorDto, BoardStateDto] =
    session.get.flatMap(sessionToDto)

  private def sessionToDto(s: SessionState): ZIO[Any, Nothing, BoardStateDto] =
    SanSerializer
      .deriveMoveLog(s.initialState, s.history)
      .map(log => WebBoardView.toDto(s.state, log, s.error))
      .orDie

  // --------------------------------------------------------------------------
  // Raw zio-http routes (HTML / JS / SSE)
  // --------------------------------------------------------------------------

  private def rawRoutes(
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit],
  ): Routes[Any, Response] =
    Routes(
      Method.GET / ""                -> handler(servePage()),
      Method.GET / "web" / "main.js" -> handler(serveJsBundle()),
      Method.GET / "web" / "pieces" / string("name") ->
        handler((name: String, _: Request) => servePieceSvg(name)),
      Method.GET / "api" / "events" -> handler(
        serveEvents(session, shutdown)
      ),
    )

  private def servePage(): ZIO[Any, Nothing, Response] =
    ZIO.succeed(
      Response(
        status  = Status.Ok,
        headers = Headers(Header.ContentType(MediaType.text.html)),
        body    = Body.fromString(HtmlPage.render),
      )
    )

  private def serveJsBundle(): ZIO[Any, Nothing, Response] =
    serveClasspathResource("web/main.js", MediaType.application.`javascript`)

  /** Serve a unified piece SVG from `web/pieces/`. The `name` segment is
    * allow-listed against `[a-z]+\.svg` so a malicious URL can't escape the
    * resource directory via `..` traversal or read arbitrary classpath
    * entries with crafted suffixes.
    */
  private def servePieceSvg(name: String): ZIO[Any, Nothing, Response] =
    if !name.matches("[a-z]+\\.svg") then
      ZIO.succeed(Response(status = Status.NotFound))
    else
      serveClasspathResource(s"web/pieces/$name", MediaType.image.`svg+xml`)

  private def serveClasspathResource(
      path: String,
      contentType: MediaType,
  ): ZIO[Any, Nothing, Response] =
    ZIO.succeed {
      val stream = getClass.getClassLoader.getResourceAsStream(path)
      if stream == null then Response(status = Status.NotFound)
      else
        val source = scala.io.Source.fromInputStream(stream)
        val content =
          try source.mkString
          finally source.close()
        Response(
          status  = Status.Ok,
          headers = Headers(Header.ContentType(contentType)),
          body    = Body.fromString(content),
        )
    }

  private def serveEvents(
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit],
  ): ZIO[Any, Nothing, Response] =
    ZIO.succeed {
      val stateEvents = session.changes.mapZIO { s =>
        sessionToDto(s)
          .map(dto =>
            ServerSentEvent(
              data      = zio.json.EncoderOps(dto).toJson,
              eventType = Some("state"),
            )
          )
      }
      val quitEvent = zio.stream.ZStream
        .fromZIO(shutdown.await)
        .map(_ =>
          ServerSentEvent(
            data      = "quit",
            eventType = Some("quit"),
          )
        )
      Response.fromServerSentEvents(stateEvents.merge(quitEvent))
    }
