package chess.tui

import chess.api.{BoardStateDto, ErrorDto, GameSnapshot}
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
  *   - an SSE subscriber on `/api/games/{id}/events` that re-renders
  *     whenever the gateway pushes new state (e.g. a move made via the
  *     web-ui)
  *
  * The shared `flipped` orientation, current `gameId`, and active SSE
  * fiber handle live in `Ref`s so REPL commands and the subscriber can
  * coordinate (e.g. `new` rebinds the SSE to a new game id).
  *
  * Reads `PICHESS_GATEWAY_URL` from the environment (default
  * `http://localhost:8090`) so the same image runs in docker-compose
  * (pointing at `gateway:8090`) and on the host (pointing at localhost).
  */
object TuiMain extends ZIOAppDefault:

  val EnvGatewayUrl: String = "PICHESS_GATEWAY_URL"
  val EnvSessionId: String = "PICHESS_SESSION_ID"

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
                       .map(msg =>
                         IllegalArgumentException(s"Bad $EnvGatewayUrl: $msg")
                       )
                   )
        backend <- HttpClientZioBackend.scoped()
        // Each TUI process gets its own session id. Override via env so
        // operators can pin a specific one when scripting (e.g. two
        // shells should share an active player on the same lobby game).
        sessionId <- zio.System.env(EnvSessionId).flatMap {
                       case Some(s) if s.trim.nonEmpty =>
                         ZIO.succeed(s.trim)
                       case _ =>
                         Random.nextUUID.map(_.toString)
                     }
        client             = TuiClient(baseUri, backend, sessionId)
        flippedRef        <- Ref.make(false)
        gameIdRef         <- Ref.make[Option[String]](None)
        subscriberHandle  <- Ref.make[Option[Fiber.Runtime[Any, Any]]](None)
        _                 <- Console.printLine(s"pichess-tui connecting to $url")
        _                 <- Console.printLine(HelpView.render)
        // Mint a fresh game and prime everything (state print + SSE).
        _                 <- bootstrap(
                               client,
                               baseUri,
                               backend,
                               gameIdRef,
                               flippedRef,
                               subscriberHandle
                             )
        _                 <- repl(
                               client,
                               baseUri,
                               backend,
                               gameIdRef,
                               flippedRef,
                               subscriberHandle
                             )
        // REPL ended (quit or EOF) — interrupt the subscriber.
        existing          <- subscriberHandle.get
        _                 <- existing.fold(ZIO.unit)(_.interrupt)
      yield ()

    ZIO.scoped(program)

  /** Create a fresh game, set up state + SSE for it. */
  private def bootstrap(
      client: TuiClient,
      baseUri: sttp.model.Uri,
      backend: sttp.client3.SttpBackend[Task, sttp.capabilities.zio.ZioStreams],
      gameIdRef: Ref[Option[String]],
      flippedRef: Ref[Boolean],
      subscriberHandle: Ref[Option[Fiber.Runtime[Any, Any]]]
  ): Task[Unit] =
    client.createGame().flatMap {
      case Right(snapshot) =>
        gameIdRef.set(Some(snapshot.id)) *>
          flippedRef.get.flatMap(flipped =>
            Console.printLine(DtoRenderer.render(snapshot.state, flipped))
          ) *>
          restartSubscriber(
            baseUri,
            backend,
            snapshot.id,
            flippedRef,
            subscriberHandle
          )
      case Left(err) =>
        Console.printLine(s"Failed to create initial game: ${err.error}")
    }

  /** Read-eval-print loop. Returns when the user types `quit` or stdin
    * closes (e.g. `docker exec -T < commands.txt` reaches EOF).
    */
  private def repl(
      client: TuiClient,
      baseUri: sttp.model.Uri,
      backend: sttp.client3.SttpBackend[Task, sttp.capabilities.zio.ZioStreams],
      gameIdRef: Ref[Option[String]],
      flippedRef: Ref[Boolean],
      subscriberHandle: Ref[Option[Fiber.Runtime[Any, Any]]]
  ): Task[Unit] =
    Console.print("> ") *>
      Console.readLine.foldZIO(
        // EOF on stdin = the script piped in has finished. Treat as quit.
        _ => Console.printLine("(stdin closed)"),
        line =>
          val command = TuiController.parseCommand(line)
          handle(
            client,
            baseUri,
            backend,
            command,
            gameIdRef,
            flippedRef,
            subscriberHandle
          ).flatMap {
            case true =>
              repl(
                client,
                baseUri,
                backend,
                gameIdRef,
                flippedRef,
                subscriberHandle
              )
            case false => ZIO.unit
          }
      )

  /** Run a single command. Returns `true` to continue the REPL, `false`
    * to terminate it.
    */
  private[tui] def handle(
      client: TuiClient,
      baseUri: sttp.model.Uri,
      backend: sttp.client3.SttpBackend[Task, sttp.capabilities.zio.ZioStreams],
      command: Command,
      gameIdRef: Ref[Option[String]],
      flippedRef: Ref[Boolean],
      subscriberHandle: Ref[Option[Fiber.Runtime[Any, Any]]]
  ): Task[Boolean] = command match
    case Command.Quit =>
      Console.printLine("bye").as(false)
    case Command.Help =>
      Console.printLine(HelpView.render).as(true)
    case Command.Flip =>
      flippedRef.updateAndGet(!_) *> printState(client, gameIdRef, flippedRef).as(true)
    case Command.Noop =>
      ZIO.succeed(true)
    case Command.Move(raw) =>
      respond(_ => withGameId(gameIdRef)(client.move(_, raw)), flippedRef)
    case Command.Undo =>
      respond(_ => withGameId(gameIdRef)(client.undo(_)), flippedRef)
    case Command.Redo =>
      respond(_ => withGameId(gameIdRef)(client.redo(_)), flippedRef)
    case Command.Draw =>
      respond(_ => withGameId(gameIdRef)(client.claimDraw(_)), flippedRef)
    case Command.Forfeit =>
      respond(_ => withGameId(gameIdRef)(client.forfeit(_)), flippedRef)
    case Command.New =>
      newOrLoad(client, baseUri, backend, gameIdRef, flippedRef, subscriberHandle, None)
    case Command.Load(raw) =>
      newOrLoad(
        client,
        baseUri,
        backend,
        gameIdRef,
        flippedRef,
        subscriberHandle,
        Some(raw)
      )
    case Command.Export(fmt) =>
      val name = fmt match
        case ExportFormat.Fen  => "fen"
        case ExportFormat.Pgn  => "pgn"
        case ExportFormat.Json => "json"
      withGameIdOptional(gameIdRef) {
        case None =>
          Console.printLine("Error: no active game").as(true)
        case Some(id) =>
          client
            .exportAs(id, name)
            .flatMap {
              case Right(resp) =>
                Console.printLine(s"=== ${resp.format.toUpperCase} ===") *>
                  Console.printLine(resp.content)
              case Left(err) =>
                Console.printLine(s"Error: ${err.error}")
            }
            .as(true)
      }

  /** Mint a new game (or load one), bind the SSE subscription to its id,
    * and render the initial state. Shared by `new` and `load`.
    */
  private def newOrLoad(
      client: TuiClient,
      baseUri: sttp.model.Uri,
      backend: sttp.client3.SttpBackend[Task, sttp.capabilities.zio.ZioStreams],
      gameIdRef: Ref[Option[String]],
      flippedRef: Ref[Boolean],
      subscriberHandle: Ref[Option[Fiber.Runtime[Any, Any]]],
      load: Option[String]
  ): Task[Boolean] =
    client.createGame(load).flatMap {
      case Right(snapshot) =>
        gameIdRef.set(Some(snapshot.id)) *>
          flippedRef.get.flatMap(flipped =>
            Console.printLine(DtoRenderer.render(snapshot.state, flipped))
          ) *>
          restartSubscriber(
            baseUri,
            backend,
            snapshot.id,
            flippedRef,
            subscriberHandle
          ).as(true)
      case Left(err) =>
        Console.printLine(s"Error: ${err.error}").as(true)
    }

  /** Read the current gameId; if absent, fail with a friendly error in
    * the REPL. Used by command handlers that need an active game.
    */
  private def withGameId(
      gameIdRef: Ref[Option[String]]
  )(
      action: String => Task[Either[ErrorDto, BoardStateDto]]
  ): Task[Either[ErrorDto, BoardStateDto]] =
    gameIdRef.get.flatMap {
      case Some(id) => action(id)
      case None     => ZIO.succeed(Left(ErrorDto("No active game")))
    }

  /** Variant for handlers that don't return a `BoardStateDto` (e.g. export). */
  private def withGameIdOptional[A](
      gameIdRef: Ref[Option[String]]
  )(action: Option[String] => Task[A]): Task[A] =
    gameIdRef.get.flatMap(action)

  private def respond(
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
      gameIdRef: Ref[Option[String]],
      flippedRef: Ref[Boolean]
  ): Task[Unit] =
    for
      flipped <- flippedRef.get
      maybeId <- gameIdRef.get
      _       <- maybeId match
                   case None =>
                     Console.printLine("(no active game — type 'new')")
                   case Some(id) =>
                     client.state(id).flatMap {
                       case Right(dto) =>
                         Console.printLine(DtoRenderer.render(dto, flipped))
                       case Left(err) =>
                         Console.printLine(s"Error: ${err.error}")
                     }
    yield ()

  /** Interrupt the current SSE subscriber (if any) and start a fresh one
    * for the given gameId. Called on initial bootstrap and after each
    * `new` / `load`.
    */
  private def restartSubscriber(
      baseUri: sttp.model.Uri,
      backend: sttp.client3.SttpBackend[Task, sttp.capabilities.zio.ZioStreams],
      gameId: String,
      flippedRef: Ref[Boolean],
      subscriberHandle: Ref[Option[Fiber.Runtime[Any, Any]]]
  ): Task[Unit] =
    for
      existing <- subscriberHandle.get
      _        <- existing.fold(ZIO.unit)(_.interrupt)
      fiber    <- subscribe(baseUri, backend, gameId, flippedRef).fork
      _        <- subscriberHandle.set(Some(fiber))
    yield ()

  /** Background fiber: subscribes to the gateway's SSE feed for `gameId`
    * and re-renders whenever an external state push arrives (e.g. a move
    * made through the web-ui). A leading newline keeps the redraw from
    * stomping on a half-typed prompt — visually noisy but never lossy.
    */
  private def subscribe(
      baseUri: sttp.model.Uri,
      backend: sttp.client3.SttpBackend[Task, sttp.capabilities.zio.ZioStreams],
      gameId: String,
      flippedRef: Ref[Boolean]
  ): Task[Unit] =
    val drain = TuiEventStream
      .subscribe(baseUri, backend, gameId)
      .tap { case TuiEventStream.Event.State(dto) =>
        for
          flipped <- flippedRef.get
          _       <- Console.printLine("\n" + DtoRenderer.render(dto, flipped))
          _       <- Console.print("> ")
        yield ()
      }
      .runDrain
      .catchAll(err =>
        Console.printLine(s"(SSE stream ended: ${err.getMessage})").orDie
      )
    Console
      .printLine(s"(SSE: subscribed to $baseUri/api/games/$gameId/events)") *>
      drain
