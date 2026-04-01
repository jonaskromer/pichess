package chess

import chess.notation.SanSerializer
import chess.model.GameId
import chess.model.board.GameState
import chess.model.piece.Color
import chess.repository.InMemoryGameRepository
import chess.service.GameService
import chess.view.{BoardView, HelpView, HtmlPage, MoveLogView, WebBoardView}
import zio.*
import zio.Console.*
import zio.http.*

object Main extends ZIOAppDefault:

  private case class SessionState(
      gameId: GameId,
      state: GameState,
      moveLog: List[(Color, String)],
      error: Option[String]
  )

  def run: ZIO[Any, Throwable, Unit] =
    (for
      gs <- ZIO.service[GameService]
      tuiEvent <- gs.newGame()
      guiEvent <- gs.newGame()
      session <- Ref.make(
        SessionState(guiEvent.gameId, guiEvent.initialState, Nil, None)
      )
      routes = makeRoutes(gs, session)
      _ <- Server
        .serve(routes)
        .zipPar(openBrowser.delay(1.second))
        .zipPar(
          tuiLoop(
            gs,
            tuiEvent.gameId,
            tuiEvent.initialState,
            flipped = false,
            moveLog = Nil
          )
        )
    yield ()).provide(
      GameService.layer,
      InMemoryGameRepository.layer,
      Server.defaultWithPort(8090)
    )

  // ─── TUI ──────────────────────────────────────────────────────────────────

  private def tuiLoop(
      gs: GameService,
      id: GameId,
      state: GameState,
      flipped: Boolean,
      moveLog: List[(Color, String)]
  ): ZIO[Any, Throwable, Unit] =
    for
      _ <- printLine(BoardView.render(state, flipped))
      _ <- ZIO.when(moveLog.nonEmpty)(printLine(MoveLogView.render(moveLog)))
      _ <- printLine(
        s"${state.activeColor}'s turn — enter a move (e.g. e2 e4), 'help', 'flip', or 'quit':"
      )
      input <- readLine
      _ <- input.trim match
        case "quit" => printLine("Goodbye!")
        case "help" =>
          printLine(HelpView.render) *> tuiLoop(
            gs,
            id,
            state,
            flipped,
            moveLog
          )
        case "flip" => tuiLoop(gs, id, state, !flipped, moveLog)
        case raw =>
          val color = state.activeColor
          gs.makeMove(id, raw)
            .foldZIO(
              err =>
                printLine(s"Error: ${err.getMessage}") *> tuiLoop(
                  gs,
                  id,
                  state,
                  flipped,
                  moveLog
                ),
              (newState, event) =>
                val san = SanSerializer.toSan(event.move, state)
                tuiLoop(gs, id, newState, flipped, moveLog :+ (color, san))
            )
    yield ()

  // ─── GUI ──────────────────────────────────────────────────────────────────

  private def makeRoutes(
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
    session.get.map(s =>
      Response.json(WebBoardView.toJson(s.state, s.moveLog, s.error))
    )

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
    yield Response.json(
      WebBoardView.toJson(updated.state, updated.moveLog, updated.error)
    )).catchAll(err =>
      ZIO.succeed(
        Response
          .json(s"""{"error":"${WebBoardView.escapeJson(err.getMessage)}"}""")
          .status(Status.BadRequest)
      )
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
    yield Response.json(
      WebBoardView.toJson(s.state, s.moveLog, s.error)
    )).catchAll(err =>
      ZIO.succeed(
        Response
          .json(s"""{"error":"${WebBoardView.escapeJson(err.getMessage)}"}""")
          .status(Status.InternalServerError)
      )
    )

  private def handleQuit(): ZIO[Any, Nothing, Response] =
    ZIO.succeed(
      Response.json("""{"quit":true}""")
    ) <* ZIO
      .sleep(500.millis)
      .flatMap(_ => ZIO.attempt(java.lang.Runtime.getRuntime.halt(0)))
      .forkDaemon

  private def extractMove(jsonBody: String): Option[String] =
    val pattern = """"move"\s*:\s*"([^"]*)"""".r
    pattern.findFirstMatchIn(jsonBody).map(_.group(1))

  private def openBrowser: Task[Unit] = ZIO.attempt {
    val os = java.lang.System.getProperty("os.name").toLowerCase
    val cmd =
      if os.contains("mac") then Array("open", "http://localhost:8090")
      else if os.contains("win") then
        Array("cmd", "/c", "start", "http://localhost:8090")
      else Array("xdg-open", "http://localhost:8090")
    java.lang.Runtime.getRuntime.exec(cmd)
    ()
  }
