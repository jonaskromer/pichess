package chess.controller

import chess.model.{GameError, GameSnapshot, SessionState}
import chess.service.GameService
import chess.view.{HtmlPage, WebBoardView}
import zio.*
import zio.http.*
import zio.schema.Schema
import zio.stream.SubscriptionRef

object WebController:

  private given Schema[String] = Schema[String]

  def routes(
      gs: GameService,
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit]
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "" -> handler(servePage()),
      Method.GET / "api" / "state" -> handler(serveState(session)),
      Method.GET / "api" / "events" -> handler(
        serveEvents(session, shutdown)
      ),
      Method.POST / "api" / "move" -> handler { (req: Request) =>
        handleMove(gs, session, req)
      },
      Method.POST / "api" / "new" -> handler(handleNewGame(gs, session)),
      Method.POST / "api" / "quit" -> handler(handleQuit(shutdown))
    )

  private def servePage(): ZIO[Any, Nothing, Response] =
    ZIO.succeed(
      Response(
        status = Status.Ok,
        headers = Headers(Header.ContentType(MediaType.text.html)),
        body = Body.fromString(HtmlPage.render)
      )
    )

  private def serveState(
      session: SubscriptionRef[SessionState]
  ): ZIO[Any, Nothing, Response] =
    session.get.map(s => stateResponse(s))

  private def serveEvents(
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit]
  ): ZIO[Any, Nothing, Response] =
    ZIO.succeed {
      val stateEvents = session.changes.map { s =>
        ServerSentEvent(
          data = WebBoardView.toJson(s.state, s.moveLog, s.error),
          eventType = Some("state")
        )
      }
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

  private def handleMove(
      gs: GameService,
      session: SubscriptionRef[SessionState],
      req: Request
  ): ZIO[Any, Nothing, Response] =
    (for
      body <- req.body.asString.mapError(e =>
        GameError.ParseError(e.getMessage)
      )
      move <- ZIO
        .fromOption(extractMove(body))
        .orElseFail(GameError.ParseError("Missing move field"))
      _ <- GameController.makeMove(gs, session, move)
      updated <- session.get
    yield stateResponse(updated)).catchAll(err =>
      ZIO.succeed(errorResponse(err.message, Status.BadRequest))
    )

  private def handleNewGame(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): ZIO[Any, Nothing, Response] =
    (for
      event <- gs.newGame()
      _ <- session.set(
        SessionState(GameSnapshot(event.gameId, event.initialState, Nil))
      )
      s <- session.get
    yield stateResponse(s)).catchAll(err =>
      ZIO.succeed(errorResponse(err.message, Status.InternalServerError))
    )

  private def handleQuit(
      shutdown: Promise[Nothing, Unit]
  ): ZIO[Any, Nothing, Response] =
    shutdown.succeed(()) *>
      ZIO.succeed(Response.json("""{"quit":true}"""))

  private def stateResponse(s: SessionState): Response =
    Response.json(WebBoardView.toJson(s.state, s.moveLog, s.error))

  private def errorResponse(message: String, status: Status): Response =
    Response
      .json(
        s"""{"error":"${WebBoardView.escapeJson(message)}"}"""
      )
      .status(status)

  private[controller] def extractMove(jsonBody: String): Option[String] =
    val pattern = """"move"\s*:\s*"([^"]*)"""".r
    pattern.findFirstMatchIn(jsonBody).map(_.group(1))
