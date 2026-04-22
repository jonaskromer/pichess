package chess.controller

import chess.api.{BoardStateDto, Endpoints, ErrorDto, MoveRequest}
import chess.api.Endpoints.QuitAck
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
        Endpoints.getState.zServerLogic[Any](_ => currentBoard(session)),
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
      ),
    )

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
    ZIO.succeed {
      val stream = getClass.getClassLoader.getResourceAsStream("web/main.js")
      if stream == null then Response(status = Status.NotFound)
      else
        val source = scala.io.Source.fromInputStream(stream)
        val content =
          try source.mkString
          finally source.close()
        Response(
          status  = Status.Ok,
          headers = Headers(
            Header.ContentType(MediaType.application.`javascript`)
          ),
          body = Body.fromString(content),
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
