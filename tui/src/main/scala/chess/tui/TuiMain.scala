package chess.tui

import chess.api.{BoardStateDto, ErrorDto}
import chess.controller.TuiController
import chess.controller.TuiController.{Command, ExportFormat}
import chess.view.HelpView
import sttp.client3.UriContext
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*

/** Entry point for the TUI runtime container.
  *
  * Reads commands from stdin, parses via [[chess.controller.TuiController]],
  * forwards each to the gateway over HTTP via [[TuiClient]], and renders
  * the result through [[DtoRenderer]]. The view-flip ('flip' command) is
  * client-local — it never reaches the gateway, since flipping just toggles
  * board orientation.
  *
  * Two concurrent fibers run side by side:
  *   - the REPL reading stdin and posting commands
  *   - an SSE subscriber on `/api/events` that re-renders whenever the
  *     gateway pushes new state (e.g. a move made via the web-ui)
  *
  * The shared `flipped` orientation lives in a `Ref` so both fibers can
  * read it without coordinating.
  *
  * Reads `PICHESS_GATEWAY_URL` from the environment (default
  * `http://localhost:8090`) so the same image runs in docker-compose
  * (pointing at `gateway:8090`) and on the host (pointing at localhost).
  */
object TuiMain extends ZIOAppDefault:

  val EnvGatewayUrl: String = "PICHESS_GATEWAY_URL"

  override def run: ZIO[ZIOAppArgs, Any, Any] =
    val program: ZIO[Scope, Throwable, Unit] =
      for
        url <- zio.System
                 .env(EnvGatewayUrl)
                 .map(_.getOrElse("http://localhost:8090"))
        baseUri <- ZIO.fromEither(
                     sttp.model.Uri
                       .parse(url)
                       .left
                       .map(msg => IllegalArgumentException(s"Bad $EnvGatewayUrl: $msg"))
                   )
        backend     <- HttpClientZioBackend.scoped()
        client      = TuiClient(baseUri, backend)
        flippedRef  <- Ref.make(false)
        _           <- Console.printLine(s"pichess-tui connecting to $url")
        _           <- Console.printLine(HelpView.render)
        // Snapshot the current state once so the user starts looking at a
        // board, not a blank prompt.
        _           <- printState(client, flippedRef)
        // Fork the SSE subscriber: pushes from the gateway redraw the
        // board live so a move made in the web-ui shows up here without
        // the user typing anything.
        subscriber  <- subscribe(baseUri, backend, flippedRef).forkScoped
        _           <- repl(client, flippedRef)
        _           <- subscriber.interrupt
      yield ()

    ZIO.scoped(program)

  /** Read-eval-print loop. Returns when the user types `quit` or stdin
    * closes (e.g. `docker exec -T < commands.txt` reaches EOF).
    */
  private def repl(
      client: TuiClient,
      flippedRef: Ref[Boolean]
  ): Task[Unit] =
    Console.print("> ") *>
      Console.readLine.foldZIO(
        // EOF on stdin = the script piped in has finished. Treat as quit.
        _ => Console.printLine("(stdin closed)"),
        line =>
          val command = TuiController.parseCommand(line)
          handle(client, command, flippedRef).flatMap {
            case true  => repl(client, flippedRef)
            case false => ZIO.unit
          }
      )

  /** Run a single command. Returns `true` to continue the REPL, `false`
    * to terminate it.
    */
  private[tui] def handle(
      client: TuiClient,
      command: Command,
      flippedRef: Ref[Boolean]
  ): Task[Boolean] = command match
    case Command.Quit =>
      Console.printLine("bye").as(false)
    case Command.Help =>
      Console.printLine(HelpView.render).as(true)
    case Command.Flip =>
      flippedRef.updateAndGet(!_).flatMap(_ => printState(client, flippedRef)).as(true)
    case Command.Noop =>
      ZIO.succeed(true)
    case Command.Move(raw) =>
      respond(client, _ => client.move(raw), flippedRef)
    case Command.Undo =>
      respond(client, _ => client.undo, flippedRef)
    case Command.Redo =>
      respond(client, _ => client.redo, flippedRef)
    case Command.Draw =>
      respond(client, _ => client.claimDraw, flippedRef)
    case Command.Forfeit =>
      respond(client, _ => client.forfeit, flippedRef)
    case Command.New =>
      respond(client, _ => client.newGame, flippedRef)
    case Command.Load(raw) =>
      respond(client, _ => client.load(raw), flippedRef)
    case Command.Export(fmt) =>
      val name = fmt match
        case ExportFormat.Fen  => "fen"
        case ExportFormat.Pgn  => "pgn"
        case ExportFormat.Json => "json"
      client
        .exportAs(name)
        .flatMap {
          case Right(resp) =>
            Console.printLine(s"=== ${resp.format.toUpperCase} ===") *>
              Console.printLine(resp.content)
          case Left(err) =>
            Console.printLine(s"Error: ${err.error}")
        }
        .as(true)

  private def respond(
      client: TuiClient,
      action: Unit => Task[Either[ErrorDto, BoardStateDto]],
      flippedRef: Ref[Boolean]
  ): Task[Boolean] =
    for
      result  <- action(())
      flipped <- flippedRef.get
      _       <- result match
                   case Right(dto) =>
                     Console.printLine(DtoRenderer.render(dto, flipped))
                   case Left(err) =>
                     Console.printLine(s"Error: ${err.error}")
    yield true

  private def printState(
      client: TuiClient,
      flippedRef: Ref[Boolean]
  ): Task[Unit] =
    for
      flipped <- flippedRef.get
      result  <- client.state
      _       <- result match
                   case Right(dto) =>
                     Console.printLine(DtoRenderer.render(dto, flipped))
                   case Left(err) =>
                     Console.printLine(
                       s"(no game yet — type 'new'?  details: ${err.error})"
                     )
    yield ()

  /** Background fiber: subscribes to the gateway's SSE feed and re-renders
    * whenever an external state push arrives (e.g. a move made through the
    * web-ui). A leading newline keeps the redraw from stomping on a half-
    * typed prompt — visually noisy but never lossy.
    */
  private def subscribe(
      baseUri: sttp.model.Uri,
      backend: sttp.client3.SttpBackend[Task, sttp.capabilities.zio.ZioStreams],
      flippedRef: Ref[Boolean]
  ): Task[Unit] =
    val drain = TuiEventStream
      .subscribe(baseUri, backend)
      .tap {
        case TuiEventStream.Event.State(dto) =>
          for
            flipped <- flippedRef.get
            _       <- Console.printLine("\n" + DtoRenderer.render(dto, flipped))
            _       <- Console.print("> ")
          yield ()
        case TuiEventStream.Event.Quit =>
          Console.printLine("(server requested shutdown)")
      }
      .runDrain
      .catchAll(err =>
        Console.printLine(s"(SSE stream ended: ${err.getMessage})").orDie
      )
    Console.printLine(s"(SSE: subscribed to $baseUri/api/events)") *> drain
