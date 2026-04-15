package chess.controller

import chess.model.{GameError, GameSnapshot, SessionState}
import chess.notation.SanSerializer
import chess.service.GameService
import chess.view.{HtmlPage, WebBoardView}
import zio.*
import zio.http.*
import zio.json.*
import zio.stream.SubscriptionRef

object WebController:

  private case class MoveRequest(move: String)
  private given JsonDecoder[MoveRequest] = DeriveJsonDecoder.gen[MoveRequest]

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
      Method.POST / "api" / "undo" -> handler(handleUndo(gs, session)),
      Method.POST / "api" / "redo" -> handler(handleRedo(gs, session)),
      Method.POST / "api" / "draw" -> handler(handleDraw(gs, session)),
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
    session.get.flatMap(stateResponse)

  private def serveEvents(
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit]
  ): ZIO[Any, Nothing, Response] =
    ZIO.succeed {
      val stateEvents = session.changes.mapZIO { s =>
        deriveJson(s).map { json =>
          ServerSentEvent(
            data = json,
            eventType = Some("state")
          )
        }
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
      moveReq <- req.body
        .asJsonFromCodec[MoveRequest]
        .mapError(e => GameError.ParseError(e.getMessage))
      _ <- GameController.makeMove(gs, session, moveReq.move)
      updated <- session.get
      resp <- stateResponse(updated)
    yield resp).catchAll(err =>
      ZIO.succeed(errorResponse(err.message, Status.BadRequest))
    )

  private def handleUndo(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): ZIO[Any, Nothing, Response] =
    (for
      _ <- GameController.undo(gs, session)
      updated <- session.get
      resp <- stateResponse(updated)
    yield resp).catchAll(err =>
      ZIO.succeed(errorResponse(err.message, Status.BadRequest))
    )

  private def handleRedo(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): ZIO[Any, Nothing, Response] =
    (for
      _ <- GameController.redo(gs, session)
      updated <- session.get
      resp <- stateResponse(updated)
    yield resp).catchAll(err =>
      ZIO.succeed(errorResponse(err.message, Status.BadRequest))
    )

  private def handleDraw(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): ZIO[Any, Nothing, Response] =
    (for
      _ <- GameController.claimDraw(gs, session)
      updated <- session.get
      resp <- stateResponse(updated)
    yield resp).catchAll(err =>
      ZIO.succeed(errorResponse(err.message, Status.BadRequest))
    )

  private def handleNewGame(
      gs: GameService,
      session: SubscriptionRef[SessionState]
  ): ZIO[Any, Nothing, Response] =
    (for
      event <- gs.newGame()
      _ <- session.set(
        SessionState(GameSnapshot(event.gameId, event.initialState))
      )
      s <- session.get
      resp <- stateResponse(s)
    yield resp).catchAll(err =>
      ZIO.succeed(errorResponse(err.message, Status.InternalServerError))
    )

  private def handleQuit(
      shutdown: Promise[Nothing, Unit]
  ): ZIO[Any, Nothing, Response] =
    shutdown.succeed(()) *>
      ZIO.succeed(Response.json("""{"quit":true}"""))

  private def stateResponse(s: SessionState): UIO[Response] =
    deriveJson(s).map(Response.json(_))

  private def deriveJson(s: SessionState): UIO[String] =
    SanSerializer
      .deriveMoveLog(s.initialState, s.history)
      .map(log => WebBoardView.toJson(s.state, log, s.error))
      .orDie

  private def errorResponse(message: String, status: Status): Response =
    Response
      .json(
        s"""{"error":"${WebBoardView.escapeJson(message)}"}"""
      )
      .status(status)
