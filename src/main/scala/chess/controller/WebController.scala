package chess.controller

import chess.model.GameId
import chess.model.board.GameState
import chess.model.piece.Color
import chess.notation.SanSerializer
import chess.service.GameService
import chess.view.{HtmlPage, WebBoardView}
import zio.*
import zio.http.*

object WebController:

  case class SessionState(
      gameId: GameId,
      state: GameState,
      moveLog: List[(Color, String)],
      error: Option[String]
  )

  def routes(
      gs: GameService,
      session: Ref[SessionState]
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "" -> handler(servePage()),
      Method.GET / "api" / "state" -> handler(serveState(session)),
      Method.POST / "api" / "move" -> handler { (req: Request) =>
        handleMove(gs, session, req)
      },
      Method.POST / "api" / "new" -> handler(handleNewGame(gs, session)),
      Method.POST / "api" / "quit" -> handler(handleQuit())
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
      session: Ref[SessionState]
  ): ZIO[Any, Nothing, Response] =
    session.get.map(s => stateResponse(s))

  private def handleMove(
      gs: GameService,
      session: Ref[SessionState],
      req: Request
  ): ZIO[Any, Nothing, Response] =
    (for
      body <- req.body.asString
      move <- ZIO
        .fromOption(extractMove(body))
        .orElseFail(new Exception("Missing move field"))
      s <- session.get
      result <- gs.makeMove(s.gameId, move)
      (newState, event) = result
      san = SanSerializer.toSan(event.move, s.state)
      _ <- session.update(st =>
        st.copy(
          state = newState,
          moveLog = st.moveLog :+ (s.state.activeColor, san),
          error = None
        )
      )
      updated <- session.get
    yield stateResponse(updated)).catchAll(err =>
      ZIO.succeed(errorResponse(err.getMessage, Status.BadRequest))
    )

  private def handleNewGame(
      gs: GameService,
      session: Ref[SessionState]
  ): ZIO[Any, Nothing, Response] =
    (for
      event <- gs.newGame()
      _ <- session.set(
        SessionState(event.gameId, event.initialState, Nil, None)
      )
      s <- session.get
    yield stateResponse(s)).catchAll(err =>
      ZIO.succeed(errorResponse(err.getMessage, Status.InternalServerError))
    )

  private def handleQuit(): ZIO[Any, Nothing, Response] =
    ZIO.succeed(
      Response.json("""{"quit":true}""")
    ) <* ZIO
      .sleep(500.millis)
      .flatMap(_ => ZIO.attempt(java.lang.Runtime.getRuntime.halt(0)))
      .forkDaemon

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
