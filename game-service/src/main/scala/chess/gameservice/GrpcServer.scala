package chess.gameservice

import scala.jdk.CollectionConverters.*

import io.grpc.{Metadata, StatusException}
import io.opentelemetry.api.trace.SpanKind
import pichess.game_service.{
  ActiveGame,
  ActiveGamesReply,
  AnalyzeReply,
  AnalyzeRequest,
  ExportReply,
  ExportRequest,
  GameIdRequest,
  ListActiveGamesRequest,
  LoadGameRequest,
  MoveRequest,
  NewGameRequest,
  ReplayReply,
  SetClockRequest,
  StateReply,
  ZioGameService
}
import scalapb.zio_grpc.RequestContext
import zio.*
import zio.json.*
import zio.stream.{Stream, SubscriptionRef, ZStream}
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import chess.analysis.{AnalysisService, CachedAnalysisService, GameAnalyzer}
import chess.api.GameAnalysisDto
import chess.bot.engine.{BotConfig, Search}
import chess.codec.{FenSerializer, JsonSerializer, PgnSerializer}
import chess.opening.EcoBook
import chess.controller.GameController
import chess.events.GameEventProducer
import chess.model.board.{GameState, Move}
import chess.model.piece.{Color, PieceType}
import chess.model.{ClockState, GameError, GameId, GameSnapshot, SessionState}
import chess.notation.SanSerializer
import chess.service.{BotConfigRepository, GameService}

/** zio-grpc service implementation. Each rpc routes the request through the
  * existing in-process `GameService` / `GameController`, then projects the
  * resulting `SessionState` into a `StateReply`. Per-game atomicity comes for
  * free from `SubscriptionRef.modifyZIO`'s semaphore inside the controllers.
  *
  * Implements the context-aware `RCGameService` variant so every rpc has access
  * to the per-call gRPC `Metadata`. The trace context (W3C `traceparent`) is
  * extracted from that metadata and used as the parent for the SERVER span this
  * server emits — so an upstream client (`TracingGameServiceClient`) and any
  * further child spans (DB calls, Kafka publish) share one trace in Jaeger.
  *
  * Errors:
  *   - `GameError.GameNotFound` → `Status.NOT_FOUND`
  *   - `GameError.InvalidMove` / `GameError.ParseError` →
  *     `Status.INVALID_ARGUMENT`
  *   - `GameError.InfrastructureError` → `Status.INTERNAL`
  */
final class GrpcServer(
    gs: GameService,
    producer: GameEventProducer,
    sessions: GameSessions,
    tracing: Tracing,
    // Vs-bot integration. These two are pure-CPU runtime dependencies;
    // the regular PvP path doesn't use them, so the layer is happy to
    // provide a trivial in-memory BotConfigRepository + the same
    // Search instance the standalone Lichess bot uses.
    botConfigs: BotConfigRepository,
    search: Search
) extends ZioGameService.RCGameService:

  def newGame(
      request: NewGameRequest,
      ctx: RequestContext
  ): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/newGame") {
      (for
        // Validate the optional bot-config fields up-front so a
        // malformed request fails before we commit a fresh game.
        botCfg <- ZIO
          .fromEither(GrpcMappers.parseBotConfig(request))
          .mapError(GameError.ParseError(_))
        event <- gs.newGame()
        _ <- botCfg.fold(ZIO.unit)(botConfigs.save(event.gameId, _))
        snapshot = GameSnapshot.fresh(event.gameId, event.initialState)
        clock0 <- clockFromRequest(request)
        ref <- sessions.register(snapshot, clock0)
        // Timed game: run the authoritative timeout daemon for its lifetime.
        _ <- ZIO.when(clock0.isDefined)(clockDaemon(ref).forkDaemon)
        // If the bot has white, play its opening move so the very
        // first state reply reflects the bot's move (the client
        // doesn't have to handle "newGame, then await first move").
        _ <- maybeBotReply(ref)
        reply <- replyFor(event.gameId, ref)
      yield reply).mapError(GrpcMappers.toStatusException)
    }

  def loadGame(
      request: LoadGameRequest,
      ctx: RequestContext
  ): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/loadGame") {
      (for
        result <- gs.loadGame(request.raw)
        (event, history) = result
        snapshot <-
          GameSnapshot.fromHistory(
            event.gameId,
            event.initialState,
            history.reverse
          )
        ref <- sessions.register(snapshot)
        reply <- replyFor(event.gameId, ref)
      yield reply).mapError(GrpcMappers.toStatusException)
    }

  def makeMove(
      request: MoveRequest,
      ctx: RequestContext
  ): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/makeMove") {
      runOn(request.gameId) { ref =>
        // Player's move first; if this is a vs-bot game and the
        // result leaves it as the bot's turn, the bot replies
        // immediately inside the same atomic block — the SSE
        // subscriber sees one update with the post-bot state, not
        // a transient "player moved, waiting" frame.
        GameController.makeMove(gs, producer, ref, request.raw) *>
          maybeBotReply(ref)
      }
    }

  def undo(
      request: GameIdRequest,
      ctx: RequestContext
  ): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/undo") {
      runOn(request.gameId)(GameController.undo(gs, producer, _))
    }

  def redo(
      request: GameIdRequest,
      ctx: RequestContext
  ): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/redo") {
      runOn(request.gameId)(GameController.redo(gs, producer, _))
    }

  def claimDraw(
      request: GameIdRequest,
      ctx: RequestContext
  ): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/claimDraw") {
      runOn(request.gameId)(GameController.claimDraw(gs, producer, _))
    }

  def forfeit(
      request: GameIdRequest,
      ctx: RequestContext
  ): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/forfeit") {
      runOn(request.gameId)(GameController.forfeit(gs, producer, _))
    }

  def setClock(
      request: SetClockRequest,
      ctx: RequestContext
  ): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/setClock") {
      Clock
        .currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        .flatMap { now =>
          runOn(request.gameId)(
            GameController.setClock(
              _,
              request.whiteMs,
              request.blackMs,
              request.running,
              now
            )
          )
        }
    }

  def getState(
      request: GameIdRequest,
      ctx: RequestContext
  ): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/getState") {
      sessions
        .get(request.gameId)
        .flatMap(replyFor(request.gameId, _))
        .mapError(GrpcMappers.toStatusException)
    }

  def replayGame(
      request: GameIdRequest,
      ctx: RequestContext
  ): IO[StatusException, ReplayReply] =
    serverSpan(ctx, "GameService/replayGame") {
      sessions
        .get(request.gameId)
        .flatMap(_.get)
        .map(s =>
          ReplayReply(gameId = request.gameId, frames = GrpcMappers.replayFrames(s))
        )
        .mapError(GrpcMappers.toStatusException)
    }

  def listActiveGames(
      request: ListActiveGamesRequest,
      ctx: RequestContext
  ): IO[StatusException, ActiveGamesReply] =
    serverSpan(ctx, "GameService/listActiveGames") {
      sessions.all.flatMap { all =>
        ZIO
          .foreach(all.filterNot(_._2.state.status.isOver)) { (id, _) =>
            botConfigs.get(id).map { cfg =>
              ActiveGame(
                gameId = id,
                vsBot = cfg.isDefined,
                botSide = cfg
                  .map(c =>
                    if c.botSide == Color.White then "white" else "black"
                  )
                  .getOrElse("")
              )
            }
          }
          .map(games => ActiveGamesReply(games = games))
      }
    }

  def exportGame(
      request: ExportRequest,
      ctx: RequestContext
  ): IO[StatusException, ExportReply] =
    serverSpan(ctx, "GameService/exportGame") {
      (for
        ref <- sessions.get(request.gameId)
        s <- ref.get
        body <- request.format.toLowerCase match
          case "fen"  => ZIO.succeed(FenSerializer.serialize(s.state))
          case "json" => ZIO.succeed(JsonSerializer.serialize(s.state))
          case "pgn" =>
            SanSerializer
              .deriveMoveLog(s.initialState, s.historyMoves)
              .orDie
              .flatMap(log => PgnSerializer.serialize(log, s.state.status))
          case other =>
            ZIO.fail(
              GameError.ParseError(
                s"Unknown format '$other'; expected fen, pgn, or json"
              )
            )
      yield ExportReply(format = request.format.toLowerCase, body = body))
        .mapError(GrpcMappers.toStatusException)
    }

  // Lazily-built, process-wide analyzer over the resident vs-bot engine + the
  // bundled ECO book (loaded once on the first analysis), with a per-PGN cache.
  private val analysisRef: Ref[Option[CachedAnalysisService]] =
    Unsafe.unsafe(implicit u => Ref.unsafe.make(None))

  private def analysisService: UIO[CachedAnalysisService] =
    analysisRef.get.flatMap {
      case Some(svc) => ZIO.succeed(svc)
      case None =>
        for
          eco <- EcoBook.load.orDie
          svc <- CachedAnalysisService.make(AnalysisService(GameAnalyzer(search, eco)))
          _   <- analysisRef.set(Some(svc))
        yield svc
    }

  def analyzeGame(
      request: AnalyzeRequest,
      ctx: RequestContext
  ): IO[StatusException, AnalyzeReply] =
    serverSpan(ctx, "GameService/analyzeGame") {
      val depth =
        if request.depth <= 0 then GrpcServer.DefaultAnalysisDepth
        else math.min(request.depth, GrpcServer.MaxAnalysisDepth)
      analysisService
        .flatMap(_.analyze(request.pgn, depth))
        .map(dto => AnalyzeReply(analysisJson = dto.toJson))
        .mapError(GrpcMappers.toStatusException)
    }

  def subscribeGame(
      request: GameIdRequest,
      ctx: RequestContext
  ): Stream[StatusException, StateReply] =
    // Streaming spans the lifetime of the subscription; we only emit a
    // span for the initial-subscribe step. The per-element work happens
    // in the upstream MakeMove rpc, not here.
    ZStream
      .fromZIO(
        sessions.get(request.gameId).mapError(GrpcMappers.toStatusException)
      )
      .flatMap { ref =>
        ref.changes.mapZIO(state =>
          GrpcMappers.toStateReply(request.gameId, state)
        )
      }

  // ---- vs-bot helpers --------------------------------------------------

  /** If the game in `ref` is in vs-bot mode AND it's now the bot's turn AND the
    * game isn't over, run the bot's search + apply its move through the same
    * [[GameController.makeMove]] path the player uses. Bot moves are recorded
    * in the same history, repetition counts include them, and the same SSE
    * subscribers see them as ordinary state updates.
    */
  private def maybeBotReply(
      ref: SubscriptionRef[SessionState]
  ): IO[GameError, Unit] =
    ref.get.flatMap { session =>
      botConfigs.get(session.gameId).flatMap {
        case Some(cfg) if botShouldPlay(session.state, cfg) =>
          playBotMove(ref, session.state, cfg)
        case _ => ZIO.unit
      }
    }

  private def botShouldPlay(state: GameState, cfg: BotConfig): Boolean =
    state.activeColor == cfg.botSide && !state.status.isOver

  /** Build the initial authoritative clock from the request, or `None` for an
    * untimed game (`initialSeconds <= 0`, the proto3 default). White's clock
    * starts running immediately (white moves first).
    */
  private def clockFromRequest(
      request: NewGameRequest
  ): UIO[Option[ClockState]] =
    if request.initialSeconds <= 0 then ZIO.none
    else
      Clock
        .currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        .map(now =>
          Some(
            ClockState.initial(
              initialMs = request.initialSeconds.toLong * 1000L,
              incrementMs = request.incrementSeconds.toLong * 1000L,
              now = now
            )
          )
        )

  /** Per-game timeout daemon for a timed game: every 250 ms, flag the side to
    * move if its clock has run out (the server is the source of truth). Exits
    * once the game is over. Coverage-excluded glue — the decision logic it
    * drives (`ClockState.flagged` + `GameController.flagIfTimedOut`) is
    * unit-tested.
    */
  private def clockDaemon(ref: SubscriptionRef[SessionState]): UIO[Unit] =
    def loop: UIO[Unit] =
      ref.get.flatMap { s =>
        if s.clock.isEmpty || s.state.status.isOver then ZIO.unit
        else
          for
            now <- Clock.currentTime(
              java.util.concurrent.TimeUnit.MILLISECONDS
            )
            _ <- GameController.flagIfTimedOut(gs, producer, ref, now).ignore
            _ <- ZIO.sleep(250.millis)
            _ <- loop
          yield ()
      }
    loop

  private def playBotMove(
      ref: SubscriptionRef[SessionState],
      state: GameState,
      cfg: BotConfig
  ): IO[GameError, Unit] =
    for
      // Project the session's per-position counts into a Set of
      // Zobrist hashes so the search treats already-seen positions
      // (including the current one) as immediate draws. This closes
      // the rules-gap the search would otherwise be blind to:
      // without `history` the bot can blunder into a 3-fold while
      // winning, or fail to claim one while losing.
      session <- ref.get
      history = session.game.positionCounts.keySet
      now <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      // Timed game: budget the reply from the bot's remaining clock (flag-safe
      // via TimeManager); untimed: the difficulty's fixed search depth.
      moveOpt <- GrpcMappers.botMoveBudgetMs(session.clock, cfg.botSide, now) match
        case Some(budgetMs) =>
          search.bestMoveWithBudget(state, budgetMs, history)
        case None =>
          search.bestMove(state, cfg.difficulty.searchDepth, history)
      move <- ZIO
        .fromOption(moveOpt)
        .orElseFail(
          GameError.InvalidMove(
            s"Bot has no legal move at a non-terminal position"
          )
        )
      // Re-enter GameController.makeMove so fivefold detection /
      // repetition counts / event publication all happen for the
      // bot's move just like for the player's.
      _ <- GameController.makeMove(gs, producer, ref, toUci(move))
    yield ()

  /** Inline UCI serialiser — bot's chosen [[Move]] → wire string the existing
    * `MoveParser` parses via its coordinate-notation branch.
    */
  private def toUci(move: Move): String =
    val base = s"${move.from.col}${move.from.row}${move.to.col}${move.to.row}"
    move.promotion match
      case Some(PieceType.Queen)  => base + "q"
      case Some(PieceType.Rook)   => base + "r"
      case Some(PieceType.Bishop) => base + "b"
      case Some(PieceType.Knight) => base + "n"
      case _                      => base

  // ---- generic helpers -------------------------------------------------

  /** Extract the parent span context from the gRPC `Metadata` carried in `ctx`,
    * then wrap `io` in a child SERVER span. The propagator reads W3C
    * `traceparent` / `tracestate` keys, populated by the
    * `TracingGameServiceClient` decorator on the gateway side.
    */
  private def serverSpan[A](ctx: RequestContext, name: String)(
      io: => IO[StatusException, A]
  ): IO[StatusException, A] =
    ctx.metadata.wrapZIO { md =>
      tracing.extractSpan(
        TraceContextPropagator.default,
        metadataCarrier(md),
        name,
        SpanKind.SERVER
      )(io)
    }

  private def metadataCarrier(
      md: Metadata
  ): IncomingContextCarrier[Metadata] =
    new IncomingContextCarrier[Metadata]:
      override val kernel: Metadata = md
      override def getAllKeys(carrier: Metadata): Iterable[String] =
        carrier.keys().asScala
      override def getByKey(carrier: Metadata, key: String): Option[String] =
        Option(
          carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER))
        )

  private def runOn(gameId: GameId)(
      action: SubscriptionRef[SessionState] => IO[GameError, Unit]
  ): IO[StatusException, StateReply] =
    (for
      ref <- sessions.get(gameId)
      _ <- action(ref)
      out <- replyFor(gameId, ref)
    yield out).mapError(GrpcMappers.toStatusException)

  private def replyFor(
      gameId: GameId,
      ref: SubscriptionRef[SessionState]
  ): UIO[StateReply] =
    ref.get.flatMap(GrpcMappers.toStateReply(gameId, _))

object GrpcServer:
  // Analysis search depth: the request's depth is used, clamped to
  // [1, MaxAnalysisDepth]; 0/absent falls back to the default (tuned for the
  // 4-vCPU deploy box).
  private val DefaultAnalysisDepth = 10
  private val MaxAnalysisDepth     = 20

  val layer: URLayer[
    GameService & GameEventProducer & GameSessions & Tracing &
      BotConfigRepository & Search,
    GrpcServer
  ] =
    ZLayer.fromFunction(GrpcServer(_, _, _, _, _, _))

  /** Layer exposing the impl as the public RC gRPC service trait — what
    * zio-grpc's `GenericBindable` derivation looks for. The trait is the
    * context-aware variant (`RCGameService = GGameService[RequestContext,
    * StatusException]`) so each rpc sees the per-call gRPC `Metadata` for trace
    * extraction.
    */
  val asServiceLayer: URLayer[
    GameService & GameEventProducer & GameSessions & Tracing &
      BotConfigRepository & Search,
    ZioGameService.RCGameService
  ] =
    ZLayer.fromFunction(
      (
          gs: GameService,
          p: GameEventProducer,
          s: GameSessions,
          t: Tracing,
          bcr: BotConfigRepository,
          search: Search
      ) =>
        new GrpcServer(gs, p, s, t, bcr, search): ZioGameService.RCGameService
    )
