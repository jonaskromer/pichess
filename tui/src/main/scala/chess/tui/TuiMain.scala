package chess.tui

import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*

import chess.api.{BoardStateDto, ErrorDto}
import chess.controller.TuiController
import chess.controller.TuiController.{Command, ExportFormat}
import chess.view.HelpView

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
  val EnvNickname: String = "PICHESS_NICKNAME"

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
        nickname          <- zio.System.env(EnvNickname).map {
                               case Some(n) if n.trim.nonEmpty => n.trim
                               case _                          => "Anonymous"
                             }
        client             = TuiClient(baseUri, backend, sessionId)
        flippedRef        <- Ref.make(false)
        gameIdRef         <- Ref.make[Option[String]](None)
        // Tracks the lobby the user is currently in (host or guest).
        // None when the user is in a local game or not in a lobby flow.
        lobbyRef          <- Ref.make[Option[TuiClient.LobbyView]](None)
        subscriberHandle  <- Ref.make[Option[Fiber.Runtime[Any, Any]]](None)
        _                 <- Console.printLine(s"pichess-tui connecting to $url as $nickname")
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
                               nickname,
                               gameIdRef,
                               flippedRef,
                               lobbyRef,
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
      nickname: String,
      gameIdRef: Ref[Option[String]],
      flippedRef: Ref[Boolean],
      lobbyRef: Ref[Option[TuiClient.LobbyView]],
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
            nickname,
            gameIdRef,
            flippedRef,
            lobbyRef,
            subscriberHandle
          ).flatMap {
            case true =>
              repl(
                client,
                baseUri,
                backend,
                nickname,
                gameIdRef,
                flippedRef,
                lobbyRef,
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
      nickname: String,
      gameIdRef: Ref[Option[String]],
      flippedRef: Ref[Boolean],
      lobbyRef: Ref[Option[TuiClient.LobbyView]],
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
    case Command.New | Command.Local =>
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
    case Command.Host(visibility) =>
      hostLobby(client, nickname, visibility, lobbyRef)
    case Command.Join(code) =>
      joinLobby(client, nickname, code, lobbyRef)
    case Command.Lobbies =>
      listLobbies(client)
    case Command.LobbyStatus =>
      showLobbyStatus(client, lobbyRef)
    case Command.Start =>
      startLobbyGame(
        client,
        baseUri,
        backend,
        gameIdRef,
        flippedRef,
        lobbyRef,
        subscriberHandle
      )
    case Command.Preview(square) =>
      previewMoves(client, gameIdRef, square)
    case Command.Threats =>
      printThreats(client, gameIdRef)

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

  // --------------------------------------------------------------------------
  // Lobby command handlers (Phase 2). Mirrors the web-ui lobby flow:
  // host → guest joins → host starts → both players land in the same game.
  // --------------------------------------------------------------------------

  private def hostLobby(
      client: TuiClient,
      nickname: String,
      visibility: TuiController.LobbyVisibility,
      lobbyRef: Ref[Option[TuiClient.LobbyView]]
  ): Task[Boolean] =
    val v = visibility match
      case TuiController.LobbyVisibility.Public  => TuiClient.Visibility.Public
      case TuiController.LobbyVisibility.Private => TuiClient.Visibility.Private
    client.createLobby(nickname, v).flatMap {
      case Right(lobby) =>
        lobbyRef.set(Some(lobby)) *>
          Console.printLine(
            s"Lobby created: invite code ${lobby.inviteCode} (${lobby.visibility})."
          ) *>
          Console.printLine(
            "Type 'lobby' to refresh status, 'start' once a guest has joined."
          ).as(true)
      case Left(err) =>
        Console.printLine(s"Error: $err").as(true)
    }

  private def joinLobby(
      client: TuiClient,
      nickname: String,
      code: String,
      lobbyRef: Ref[Option[TuiClient.LobbyView]]
  ): Task[Boolean] =
    if code.isEmpty then
      Console.printLine("Usage: join <invite-code>").as(true)
    else
      client.joinLobbyByCode(code, nickname).flatMap {
        case Right(lobby) =>
          lobbyRef.set(Some(lobby)) *>
            Console.printLine(
              s"Joined lobby ${lobby.inviteCode}. Waiting for the host " +
                "to type 'start' — use 'lobby' to recheck status."
            ).as(true)
        case Left(err) =>
          Console.printLine(s"Error: $err").as(true)
      }

  private def listLobbies(client: TuiClient): Task[Boolean] =
    client.listPublicLobbies().flatMap {
      case Right(Nil) =>
        Console.printLine("(no public lobbies)").as(true)
      case Right(lobbies) =>
        ZIO
          .foreachDiscard(lobbies) { l =>
            val guest = l.guestNickname.fold("(waiting)")(identity)
            Console.printLine(
              s"  ${l.inviteCode} — host=${l.hostNickname} guest=$guest " +
                s"status=${l.status}"
            )
          }
          .as(true)
      case Left(err) =>
        Console.printLine(s"Error: $err").as(true)
    }

  /** Refresh the cached lobby (by invite code, since that's the durable
    * handle the user knows) and print a short summary. If the lobby has
    * already been Started by the host, prints the gameId so the user knows
    * to switch over.
    */
  private def showLobbyStatus(
      client: TuiClient,
      lobbyRef: Ref[Option[TuiClient.LobbyView]]
  ): Task[Boolean] =
    lobbyRef.get.flatMap {
      case None =>
        Console
          .printLine("(no active lobby — use 'host' or 'join <code>')")
          .as(true)
      case Some(cached) =>
        client.findLobbyByCode(cached.inviteCode).flatMap {
          case Right(fresh) =>
            lobbyRef.set(Some(fresh)) *> Console.printLine(formatLobby(fresh)).as(true)
          case Left(err) =>
            Console.printLine(s"Error refreshing lobby: $err").as(true)
        }
    }

  private def formatLobby(l: TuiClient.LobbyView): String =
    val guest = l.guestNickname.fold("(waiting)")(identity)
    val game = l.gameId.fold("")(g => s"  gameId=$g")
    s"Lobby ${l.inviteCode}: host=${l.hostNickname} guest=$guest " +
      s"visibility=${l.visibility} status=${l.status}$game"

  /** Host-only: create a fresh game on the gateway, then tell the
    * lobby-service to mark the lobby as Started. The lobby-service in turn
    * notifies the gateway so guest+host both become legal players.
    * Switches the TUI's current game to the new one and rebinds SSE.
    */
  private def startLobbyGame(
      client: TuiClient,
      baseUri: sttp.model.Uri,
      backend: sttp.client3.SttpBackend[Task, sttp.capabilities.zio.ZioStreams],
      gameIdRef: Ref[Option[String]],
      flippedRef: Ref[Boolean],
      lobbyRef: Ref[Option[TuiClient.LobbyView]],
      subscriberHandle: Ref[Option[Fiber.Runtime[Any, Any]]]
  ): Task[Boolean] =
    lobbyRef.get.flatMap {
      case None =>
        Console
          .printLine("(no active lobby — use 'host' first)")
          .as(true)
      case Some(lobby) =>
        // Each `Either`-returning client call is lifted into the workflow's
        // failure channel (String error messages) so the steps chain via a
        // single linear for-comp instead of cascading match-pyramids. Any
        // upstream failure surfaces through `catchAll` as a one-line print
        // and the REPL keeps running.
        val workflow: ZIO[Any, String, Unit] =
          for
            fresh    <- liftEither(
                          client.findLobbyByCode(lobby.inviteCode),
                          e => s"Error refreshing lobby: $e"
                        )
            // Refreshed lobby tells us whether a guest has joined yet —
            // lobby-service rejects `start` for any status other than Full.
            _        <- failIf(
                          fresh.status != "Full",
                          s"Cannot start: lobby status is ${fresh.status} " +
                            "(need Full). Wait for a guest to join."
                        )
            snapshot <- liftEither(
                          client.createGame(),
                          e => s"Error creating game: ${e.error}"
                        )
            updated  <- liftEither(
                          client.startLobby(fresh.id, snapshot.id),
                          e => s"Error starting lobby: $e"
                        )
            _        <- lobbyRef.set(Some(updated))
            _        <- gameIdRef.set(Some(snapshot.id))
            flipped  <- flippedRef.get
            _        <- Console
                          .printLine(DtoRenderer.render(snapshot.state, flipped))
                          .mapError(_.getMessage)
            _        <- restartSubscriber(
                          baseUri, backend, snapshot.id, flippedRef, subscriberHandle
                        ).mapError(_.getMessage)
          yield ()
        workflow.foldZIO(
          msg => Console.printLine(msg).as(true),
          _   => ZIO.succeed(true)
        )
    }

  /** Lift a `Task[Either[E, A]]` into the lobby-workflow's String error
    * channel. The `toMsg` callback formats the domain error for the
    * user; an unexpected Throwable bubbles up as `s"Unexpected: …"`. */
  private def liftEither[E, A](
      io: Task[Either[E, A]],
      toMsg: E => String
  ): ZIO[Any, String, A] =
    io.foldZIO(
      t => ZIO.fail(s"Unexpected: ${t.getMessage}"),
      {
        case Right(a) => ZIO.succeed(a)
        case Left(e)  => ZIO.fail(toMsg(e))
      }
    )

  /** Inline guard inside the lobby-workflow for-comp — fails the chain
    * with `msg` when the condition holds, otherwise succeeds with unit. */
  private def failIf(cond: Boolean, msg: => String): ZIO[Any, String, Unit] =
    if cond then ZIO.fail(msg) else ZIO.unit

  // --------------------------------------------------------------------------
  // Annotation command handlers (Phase 3). Backed by the gateway's cache,
  // so successive calls between mutations are cheap.
  // --------------------------------------------------------------------------

  private def previewMoves(
      client: TuiClient,
      gameIdRef: Ref[Option[String]],
      square: String
  ): Task[Boolean] =
    val sq = square.trim.toLowerCase
    if sq.isEmpty then Console.printLine("Usage: preview <square>").as(true)
    else
      gameIdRef.get.flatMap {
        case None =>
          Console.printLine("(no active game — type 'new' or 'host')").as(true)
        case Some(id) =>
          client.legalMoves(id, sq).flatMap {
            case Right(r) if r.moves.isEmpty =>
              Console.printLine(s"No legal moves from $sq").as(true)
            case Right(r) =>
              Console
                .printLine(s"From $sq → ${r.moves.mkString(", ")}")
                .as(true)
            case Left(err) =>
              Console.printLine(s"Error: ${err.error}").as(true)
          }
      }

  private def printThreats(
      client: TuiClient,
      gameIdRef: Ref[Option[String]]
  ): Task[Boolean] =
    gameIdRef.get.flatMap {
      case None =>
        Console.printLine("(no active game — type 'new' or 'host')").as(true)
      case Some(id) =>
        client.threats(id).flatMap {
          case Right(r) if r.threatened.isEmpty =>
            Console.printLine("No own pieces under attack.").as(true)
          case Right(r) =>
            Console
              .printLine(s"Threatened: ${r.threatened.mkString(", ")}")
              .as(true)
          case Left(err) =>
            Console.printLine(s"Error: ${err.error}").as(true)
        }
    }

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
