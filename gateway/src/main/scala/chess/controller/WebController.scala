package chess.controller

import io.grpc.StatusException
import pichess.game_service.{
  AnalyzeRequest,
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
import zio.json.*
import zio.stream.ZStream
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing

import chess.api.{
  AnalyzeRequestDto,
  AnnotationsDto,
  AttackersResponse,
  BoardStateDto,
  Endpoints,
  ErrorDto,
  ExportResponse,
  GameAnalysisDto,
  GameSnapshot,
  LegalMovesResponse,
  ReplayFrame,
  ReplayResponse,
  StackInfoResponse,
  StateResponse,
  ThreatsResponse
}
import chess.view.HtmlPage

/** Tapir-backed REST surface plus raw zio-http routes (HTML, web-ui assets,
  * SSE). Every command endpoint is a thin shim over the gameService gRPC
  * client; the gateway holds **no** authoritative game state.
  *
  * **Routing**: every per-game endpoint is `/api/games/{id}/...`. The gateway
  * is stateless — there's no global "active game" any more. The client tracks
  * its own current gameId (in URL hash on the web-ui, in a Ref on the TUI).
  * `POST /api/games` mints a new game and returns its id alongside the initial
  * state.
  */
object WebController:

  def routes(
      client: ZioGameService.GameServiceClient,
      registry: SessionRegistry,
      cache: AnnotationCache,
      presence: SpectatorPresence,
      lobbyBaseUrl: String,
      stackInfo: StackInfo,
      lichessToken: Option[String],
      tournamentBaseUrl: String,
      botControlUrl: String,
      botName: String,
      tournamentSpectate: TournamentSpectate
  ): Routes[Client & Tracing & ContextStorage, Response] =
    val lichessRoutes =
      lichessToken match
        case Some(token) => LichessSpectate.routes(client, token)
        case None        => Routes.empty
    tapirRoutes(client, registry, cache, presence, stackInfo) ++
      rawRoutes(client, presence, stackInfo, lichessToken.isDefined) ++
      LobbyProxy.routes(lobbyBaseUrl) ++
      TournamentProxy.routes(tournamentBaseUrl, botControlUrl) ++
      tournamentSpectate.routes(client, tournamentBaseUrl) ++
      SpectateIndex.routes(
        client,
        presence,
        tournamentBaseUrl,
        botControlUrl,
        botName,
        lichessToken
      ) ++
      lichessRoutes

  // --------------------------------------------------------------------------
  // Tapir-backed JSON API
  // --------------------------------------------------------------------------

  private def tapirRoutes(
      client: ZioGameService.GameServiceClient,
      registry: SessionRegistry,
      cache: AnnotationCache,
      presence: SpectatorPresence,
      stackInfo: StackInfo
  ): Routes[Any, Response] =
    val swagger = SwaggerInterpreter()
      .fromEndpoints[Task](Endpoints.all, "pichess API", "0.1.0")
    ZioHttpInterpreter().toHttp(
      swagger ++ List(
        Endpoints.postCreateGame.zServerLogic[Any] { case (sessionId, req) =>
          val create = req.load match
            case None       => client.newGame(newGameRequestFor(req))
            case Some(load) => client.loadGame(loadGameRequestFor(load, req))
          for
            reply <- create.mapError(toErrorDto)
            // Local-game registration: the creator is the only player and
            // is allowed to move both colours. Lobby-created games will
            // overwrite this via `registerLobby` when the lobby starts
            // (Phase 2).
            _ <- registry.registerLocal(reply.gameId, sessionId)
            dto <- replyToDto(reply)
          yield GameSnapshot(reply.gameId, dto)
        },
        Endpoints.getState.zServerLogic[Any] { case (id, format) =>
          format match
            case None | Some("view") =>
              currentBoard(client, id).map(StateResponse.View(_))
            case Some(other) =>
              exportInFormat(client, id, other).map(StateResponse.Export(_))
        },
        Endpoints.postMove.zServerLogic[Any] { case (id, sessionId, req) =>
          gated(registry, id, sessionId) {
            for
              reply <- client
                .makeMove(MoveRequest(id, req.move))
                .mapError(toErrorDto)
              _ <- cache.invalidate(id)
              dto <- replyToDto(reply)
            yield dto
          }
        },
        Endpoints.postUndo.zServerLogic[Any] { case (id, sessionId) =>
          gated(registry, id, sessionId) {
            callOnGame(client, id, c => g => c.undo(GameIdRequest(g)))
              .tap(_ => cache.invalidate(id))
          }
        },
        Endpoints.postRedo.zServerLogic[Any] { case (id, sessionId) =>
          gated(registry, id, sessionId) {
            callOnGame(client, id, c => g => c.redo(GameIdRequest(g)))
              .tap(_ => cache.invalidate(id))
          }
        },
        Endpoints.postDraw.zServerLogic[Any] { case (id, sessionId) =>
          gated(registry, id, sessionId) {
            callOnGame(client, id, c => g => c.claimDraw(GameIdRequest(g)))
              .tap(_ => cache.invalidate(id))
          }
        },
        Endpoints.postForfeit.zServerLogic[Any] { case (id, sessionId) =>
          gated(registry, id, sessionId) {
            callOnGame(client, id, c => g => c.forfeit(GameIdRequest(g)))
              .tap(_ => cache.invalidate(id))
          }
        },
        Endpoints.getExport.zServerLogic[Any] { case (id, format) =>
          exportInFormat(client, id, format)
        },
        Endpoints.getReplay.zServerLogic[Any] { id =>
          replayFor(client, id)
        },
        Endpoints.postAnalyze.zServerLogic[Any] { req =>
          analyzeFor(client, req)
        },
        Endpoints.postRegisterPlayers.zServerLogic[Any] { case (id, req) =>
          // Internal coordination from lobby-service. Overwrites any
          // previous local-only registration for `id` so both lobby
          // players can mutate the game; spectators stay SSE-only. The
          // lobby's spectator policy (allowSpectate + limit) rides along
          // so the SSE handler can admit or refuse watchers.
          presence.setPolicy(id, req.allowSpectate, req.spectatorLimit) *> {
            req.guestSessionId match
              case Some(guest) =>
                registry.registerLobby(id, req.hostSessionId, guest)
              case None =>
                registry.registerLocal(id, req.hostSessionId)
          }
        },
        Endpoints.getStackInfo.zServerLogic[Any] { _ =>
          // Pure config lookup — no I/O, no game-service hop.
          ZIO.succeed(StackInfoResponse(stackInfo.backend, stackInfo.extras))
        },
        Endpoints.getLegalMoves.zServerLogic[Any] { case (id, from) =>
          annotationsFor(client, cache, id).map { ann =>
            // Empty list when the source square doesn't appear in the cache
            // (no active-color piece there). Same shape the rules helper
            // returns directly, so the UI doesn't need a special branch.
            LegalMovesResponse(
              from = from,
              moves = ann.legalMovesFrom.getOrElse(from, Nil)
            )
          }
        },
        Endpoints.getThreats.zServerLogic[Any] { id =>
          annotationsFor(client, cache, id).map(ann =>
            ThreatsResponse(threatened = ann.threats)
          )
        },
        Endpoints.getAttackers.zServerLogic[Any] { case (id, of) =>
          annotationsFor(client, cache, id).map { ann =>
            AttackersResponse(
              of = of,
              attackers = ann.attackersOf.getOrElse(of, Nil)
            )
          }
        }
      )
    )

  /** Cache-aware accessor for the annotation bundle of `gameId`. On hit,
    * returns the cached bundle directly. On miss, fetches the state from
    * gameService and decodes the server-side annotations sidecar
    * (`reply.annotations` — boopickle [[AnnotationsDto]]) directly into the
    * cache. No fallback path: game-service always populates the annotations on
    * every StateReply (see `chess.gameservice.GrpcMappers.buildAnnotations`);
    * empty or malformed bytes would be a server defect, surfaced via `.orDie`.
    *
    * Invalidation is the responsibility of the mutation handlers (`postMove` /
    * undo / redo / draw / forfeit) — they all call `cache.invalidate(id)` after
    * a successful mutation.
    */
  /** Build the gRPC `NewGameRequest` from the client's [[CreateGameRequest]].
    * The optional vs-bot settings route the game through the vs-bot orchestrator
    * (validated server-side, so the gateway just shuttles the strings).
    */
  private[controller] def newGameRequestFor(
      req: chess.api.CreateGameRequest
  ): NewGameRequest =
    req.vsBot match
      case None =>
        NewGameRequest()
      case Some(s) =>
        NewGameRequest(
          vsBot = true,
          botSide = s.botSide,
          botDifficulty = s.difficulty,
          allowUndo = s.allowUndo
        )

  /** Build the gRPC `LoadGameRequest` from the client's [[CreateGameRequest]]:
    * the imported position plus the same optional vs-bot settings
    * `newGameRequestFor` forwards. Without this the load path would silently
    * drop the bot, so "Vs Bot" + a pasted FEN/PGN produced a botless game.
    */
  private[controller] def loadGameRequestFor(
      load: String,
      req: chess.api.CreateGameRequest
  ): LoadGameRequest =
    req.vsBot match
      case None =>
        LoadGameRequest(raw = load)
      case Some(s) =>
        LoadGameRequest(
          raw = load,
          vsBot = true,
          botSide = s.botSide,
          botDifficulty = s.difficulty,
          allowUndo = s.allowUndo
        )

  private def annotationsFor(
      client: ZioGameService.GameServiceClient,
      cache: AnnotationCache,
      gameId: String
  ): ZIO[Any, ErrorDto, AnnotationCache.Annotations] =
    cache.get(gameId).flatMap {
      case Some(cached) => ZIO.succeed(cached)
      case None =>
        for
          reply <- client.getState(GameIdRequest(gameId)).mapError(toErrorDto)
          ann = decodeServerAnnotations(reply.annotations.toByteArray)
          _ <- cache.put(gameId, ann)
        yield ann
    }

  /** Phase 4: decode the server-side annotation bundle off the wire. Throws on
    * empty/corrupt input — that's a server defect, not client-recoverable.
    */
  private[controller] def decodeServerAnnotations(
      bytes: Array[Byte]
  ): AnnotationCache.Annotations =
    val dto = AnnotationsDto.decodeBytes(bytes)
    AnnotationCache.Annotations(
      legalMovesFrom = dto.legalMovesFrom,
      threats = dto.threats,
      attackersOf = dto.attackersOf
    )

  /** Wrap a mutation handler in a session-id check. Refuses with a "Forbidden:
    * ..." 400 when the session isn't a registered active player on the game.
    * Tapir's `errorOut` gives us 400; spec-wise this is a 403 in spirit, but
    * adding the second status code would mean a `oneOf[ApiError]` refactor we
    * don't need yet — the message carries the intent.
    */
  private def gated[A](
      registry: SessionRegistry,
      gameId: String,
      sessionId: String
  )(action: ZIO[Any, ErrorDto, A]): ZIO[Any, ErrorDto, A] =
    registry.canMutate(gameId, sessionId).flatMap {
      case true => action
      case false =>
        ZIO.fail(
          ErrorDto(
            s"Forbidden: session $sessionId is not an active player on game $gameId"
          )
        )
    }

  private def callOnGame(
      client: ZioGameService.GameServiceClient,
      id: String,
      action: ZioGameService.GameServiceClient => String => IO[
        StatusException,
        StateReply
      ]
  ): ZIO[Any, ErrorDto, BoardStateDto] =
    for
      reply <- action(client)(id).mapError(toErrorDto)
      dto <- replyToDto(reply)
    yield dto

  private def exportInFormat(
      client: ZioGameService.GameServiceClient,
      id: String,
      format: String
  ): ZIO[Any, ErrorDto, ExportResponse] =
    for reply <- client
        .exportGame(ExportRequest(id, format))
        .mapError(toErrorDto)
    yield ExportResponse(reply.format, reply.body)

  /** Proxy analysis to game-service (which reuses its resident engine) and
    * decode the returned `GameAnalysisDto` JSON. */
  private def analyzeFor(
      client: ZioGameService.GameServiceClient,
      req: AnalyzeRequestDto
  ): ZIO[Any, ErrorDto, GameAnalysisDto] =
    for
      reply <- client
        .analyzeGame(AnalyzeRequest(req.pgn, req.depth))
        .mapError(toErrorDto)
      dto <- ZIO.fromEither(decodeAnalysis(reply.analysisJson))
    yield dto

  /** Decode game-service's analysis JSON into the api DTO. `private[controller]`
    * so the malformed-JSON guard (unreachable through the well-behaved
    * game-service) is unit-testable. */
  private[controller] def decodeAnalysis(
      json: String
  ): Either[ErrorDto, GameAnalysisDto] =
    json.fromJson[GameAnalysisDto].left.map(e =>
      ErrorDto(s"Failed to decode analysis: $e")
    )

  private[controller] def toErrorDto(err: StatusException): ErrorDto =
    val description =
      Option(err.getStatus.getDescription).getOrElse(err.getMessage)
    ErrorDto(description)

  private def currentBoard(
      client: ZioGameService.GameServiceClient,
      id: String
  ): ZIO[Any, ErrorDto, BoardStateDto] =
    for
      reply <- client.getState(GameIdRequest(id)).mapError(toErrorDto)
      dto <- replyToDto(reply)
    yield dto

  /** Decode the gRPC StateReply's `board_state` bytes back into the shared
    * [[BoardStateDto]]. The bytes were produced by
    * [[chess.gameservice.GrpcMappers.encodeBoardState]] using
    * [[BoardStateDto.encodeBytes]] (boopickle) — same codec used here, so the
    * round-trip is exact.
    */
  private[controller] def replyToDto(
      reply: StateReply
  ): ZIO[Any, ErrorDto, BoardStateDto] =
    ZIO
      .attempt(BoardStateDto.decodeBytes(reply.boardState.toByteArray))
      .mapError(t =>
        ErrorDto(s"Failed to decode StateReply.boardState: ${t.getMessage}")
      )

  /** Fetch the full replay (every position, oldest first) and decode each
    * frame's boopickle `board_state` bytes into a [[BoardStateDto]] — the same
    * round-trip as [[replyToDto]], one frame at a time. Read-only; the
    * game-service projects the stored history without mutating the session.
    */
  private def replayFor(
      client: ZioGameService.GameServiceClient,
      id: String
  ): ZIO[Any, ErrorDto, ReplayResponse] =
    for
      reply  <- client.replayGame(GameIdRequest(id)).mapError(toErrorDto)
      frames <- ZIO.foreach(reply.frames.toList)(frameToDto)
    yield ReplayResponse(reply.gameId, frames)

  /** Decode one replay frame's boopickle `board_state` bytes into the api
    * [[ReplayFrame]] DTO — the per-ply analogue of [[replyToDto]].
    * `private[controller]` so the decode-error guard (unreachable through the
    * well-behaved game-service) is unit-testable with synthetic bad bytes. */
  private[controller] def frameToDto(
      frame: pichess.game_service.ReplayFrame
  ): ZIO[Any, ErrorDto, ReplayFrame] =
    ZIO
      .attempt(BoardStateDto.decodeBytes(frame.boardState.toByteArray))
      .mapBoth(
        t => ErrorDto(s"Failed to decode replay frame: ${t.getMessage}"),
        dto => ReplayFrame(frame.moveIndex, dto, frame.san)
      )

  // --------------------------------------------------------------------------
  // Raw zio-http routes (HTML / JS / SSE)
  // --------------------------------------------------------------------------

  private def rawRoutes(
      client: ZioGameService.GameServiceClient,
      presence: SpectatorPresence,
      stackInfo: StackInfo,
      lichessEnabled: Boolean
  ): Routes[Any, Response] =
    val core = Routes(
      Method.GET / "" -> handler(servePage(stackInfo, lichessEnabled)),
      Method.GET / "web" / trailing ->
        handler((rest: zio.http.Path, _: Request) => serveAsset(rest)),
      Method.GET / "api" / "games" / string("id") / "events" -> handler {
        (id: String, req: Request) =>
          // `?role=spectator` (set by the web-ui Watch screen) marks this
          // connection as a spectator so it's counted in the live tally;
          // players (Game screen) receive the count but aren't part of it.
          val spectator =
            req.url.queryParams.getAll("role").contains("spectator")
          serveEvents(client, presence, id, spectator)
      }
    )
    // /dev/coverage/report/** and /dev/performance/report/** are baked
    // into the gateway image at build time by `make coverage-build` /
    // `make gatling-build`. They're gated behind PICHESS_DEV — the
    // routes return 404 when the flag is off so the dev surface
    // doesn't ship in non-dev deployments.
    val dev =
      if stackInfo.devMode then
        Routes(
          Method.GET / "dev" / "coverage" / "report" / trailing ->
            handler((rest: zio.http.Path, _: Request) =>
              serveDevAsset("coverage/report", rest)
            ),
          Method.GET / "dev" / "performance" / "report" / trailing ->
            handler((rest: zio.http.Path, _: Request) =>
              serveDevAsset("performance/report", rest)
            )
        )
      else Routes.empty
    core ++ dev

  private def servePage(
      stackInfo: StackInfo,
      lichessEnabled: Boolean
  ): ZIO[Any, Nothing, Response] =
    ZIO.succeed(
      Response(
        status = Status.Ok,
        headers = Headers(Header.ContentType(MediaType.text.html)),
        body = Body.fromString(
          HtmlPage.render(
            devMode = stackInfo.devMode,
            lichessEnabled = lichessEnabled
          )
        )
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

  /** Serve a file from `dev/<subdir>/` in the gateway resources tree. Same
    * path-traversal + extension allow-list as `serveAsset`. Returns
    * `index.html` when the request lands on the directory root (e.g.
    * `/dev/coverage/report/`). The dev report HTMLs link out to .css / .js /
    * .png siblings — the existing allow-list already covers them.
    */
  private def serveDevAsset(
      subdir: String,
      rest: zio.http.Path
  ): ZIO[Any, Nothing, Response] =
    val raw = rest.toString.stripPrefix("/")
    val relative =
      if raw.isEmpty || raw.endsWith("/") then raw + "index.html"
      else raw
    contentTypeFor(relative) match
      case Some(contentType) if isSafeAssetPath(relative) =>
        serveClasspathResource(s"dev/$subdir/$relative", contentType)
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

  private[controller] def contentTypeFor(path: String): Option[MediaType] =
    val lower = path.toLowerCase
    if lower.endsWith(".js") then Some(MediaType.application.`javascript`)
    else if lower.endsWith(".svg") then Some(MediaType.image.`svg+xml`)
    else if lower.endsWith(".css") then Some(MediaType.text.css)
    else if lower.endsWith(".png") then Some(MediaType.image.png)
    else if lower.endsWith(".html") then Some(MediaType.text.html)
    else if lower.endsWith(".json") then Some(MediaType.application.json)
    else if lower.endsWith(".woff2") then Some(MediaType.font.`woff2`)
    else if lower.endsWith(".woff") then Some(MediaType.font.`woff`)
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
      presence: SpectatorPresence,
      id: String,
      spectator: Boolean
  ): ZIO[Any, Nothing, Response] =
    ZIO.succeed {
      val stateEvents = client
        .subscribeGame(GameIdRequest(id))
        .catchAll(_ => ZStream.empty)
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

      // The `spectators` SSE event carries the live count to every viewer.
      val countEvents =
        presence
          .changes(id)
          .map(n =>
            ServerSentEvent(data = n.toString, eventType = Some("spectators"))
          )

      // A spectator must be admitted under the game's policy (allowSpectate
      // + spectatorLimit): a refusal yields a single `spectator-denied`
      // event and no board, and occupies no slot. Players read the count
      // but never occupy a spectator slot. Admission is scoped to the SSE
      // stream, so a disconnect releases the slot.
      val body =
        if spectator then
          ZStream.unwrapScoped {
            presence
              .admit(id)
              .foldZIO(
                rejection =>
                  ZIO.succeed(
                    ZStream.succeed(
                      ServerSentEvent(
                        data = rejection.code,
                        eventType = Some("spectator-denied")
                      )
                    )
                  ),
                _ => ZIO.succeed(stateEvents.merge(countEvents))
              )
          }
        else stateEvents.merge(countEvents)

      Response.fromServerSentEvents(body)
    }
