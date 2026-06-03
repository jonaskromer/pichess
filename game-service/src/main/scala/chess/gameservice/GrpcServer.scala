package chess.gameservice

import chess.codec.{FenSerializer, JsonSerializer, PgnSerializer}
import chess.controller.GameController
import chess.events.GameEventProducer
import chess.model.{GameError, GameId, GameSnapshot, SessionState}
import chess.notation.SanSerializer
import chess.service.GameService
import io.grpc.{Metadata, StatusException}
import io.opentelemetry.api.trace.SpanKind
import pichess.game_service.{
  ExportReply,
  ExportRequest,
  GameIdRequest,
  LoadGameRequest,
  MoveRequest,
  NewGameRequest,
  StateReply,
  ZioGameService
}
import scalapb.zio_grpc.RequestContext
import zio.*
import zio.stream.{Stream, SubscriptionRef, ZStream}
import zio.telemetry.opentelemetry.context.IncomingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.jdk.CollectionConverters.*

/** zio-grpc service implementation. Each rpc routes the request through the
  * existing in-process `GameService` / `GameController`, then projects the
  * resulting `SessionState` into a `StateReply`. Per-game atomicity comes
  * for free from `SubscriptionRef.modifyZIO`'s semaphore inside the
  * controllers.
  *
  * Implements the context-aware `RCGameService` variant so every rpc has
  * access to the per-call gRPC `Metadata`. The trace context (W3C
  * `traceparent`) is extracted from that metadata and used as the parent
  * for the SERVER span this server emits — so an upstream client
  * (`TracingGameServiceClient`) and any further child spans (DB calls,
  * Kafka publish) share one trace in Jaeger.
  *
  * Errors:
  *   - `GameError.GameNotFound` → `Status.NOT_FOUND`
  *   - `GameError.InvalidMove` / `GameError.ParseError` → `Status.INVALID_ARGUMENT`
  *   - `GameError.InfrastructureError` → `Status.INTERNAL`
  */
final class GrpcServer(
    gs: GameService,
    producer: GameEventProducer,
    sessions: GameSessions,
    tracing: Tracing
) extends ZioGameService.RCGameService:

  def newGame(request: NewGameRequest, ctx: RequestContext): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/newGame") {
      (for
        event   <- gs.newGame()
        snapshot = GameSnapshot.fresh(event.gameId, event.initialState)
        ref     <- sessions.register(snapshot)
        reply   <- replyFor(event.gameId, ref)
      yield reply).mapError(GrpcMappers.toStatusException)
    }

  def loadGame(request: LoadGameRequest, ctx: RequestContext): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/loadGame") {
      (for
        result <- gs.loadGame(request.raw)
        (event, history) = result
        snapshot <-
          GameSnapshot.fromHistory(event.gameId, event.initialState, history.reverse)
        ref   <- sessions.register(snapshot)
        reply <- replyFor(event.gameId, ref)
      yield reply).mapError(GrpcMappers.toStatusException)
    }

  def makeMove(request: MoveRequest, ctx: RequestContext): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/makeMove") {
      runOn(request.gameId) { ref =>
        GameController.makeMove(gs, producer, ref, request.raw)
      }
    }

  def undo(request: GameIdRequest, ctx: RequestContext): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/undo") {
      runOn(request.gameId)(GameController.undo(gs, producer, _))
    }

  def redo(request: GameIdRequest, ctx: RequestContext): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/redo") {
      runOn(request.gameId)(GameController.redo(gs, producer, _))
    }

  def claimDraw(request: GameIdRequest, ctx: RequestContext): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/claimDraw") {
      runOn(request.gameId)(GameController.claimDraw(gs, producer, _))
    }

  def forfeit(request: GameIdRequest, ctx: RequestContext): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/forfeit") {
      runOn(request.gameId)(GameController.forfeit(gs, producer, _))
    }

  def getState(request: GameIdRequest, ctx: RequestContext): IO[StatusException, StateReply] =
    serverSpan(ctx, "GameService/getState") {
      sessions
        .get(request.gameId)
        .flatMap(replyFor(request.gameId, _))
        .mapError(GrpcMappers.toStatusException)
    }

  def exportGame(request: ExportRequest, ctx: RequestContext): IO[StatusException, ExportReply] =
    serverSpan(ctx, "GameService/exportGame") {
      (for
        ref <- sessions.get(request.gameId)
        s   <- ref.get
        body <- request.format.toLowerCase match
                  case "fen"  => ZIO.succeed(FenSerializer.serialize(s.state))
                  case "json" => ZIO.succeed(JsonSerializer.serialize(s.state))
                  case "pgn"  =>
                    SanSerializer
                      .deriveMoveLog(s.initialState, s.history)
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

  def subscribeGame(
      request: GameIdRequest,
      ctx: RequestContext
  ): Stream[StatusException, StateReply] =
    // Streaming spans the lifetime of the subscription; we only emit a
    // span for the initial-subscribe step. The per-element work happens
    // in the upstream MakeMove rpc, not here.
    ZStream
      .fromZIO(sessions.get(request.gameId).mapError(GrpcMappers.toStatusException))
      .flatMap { ref =>
        ref.changes.mapZIO(state => GrpcMappers.toStateReply(request.gameId, state))
      }

  // ---- helpers ---------------------------------------------------------

  /** Extract the parent span context from the gRPC `Metadata` carried in
    * `ctx`, then wrap `io` in a child SERVER span. The propagator reads
    * W3C `traceparent` / `tracestate` keys, populated by the
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
        Option(carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)))

  private def runOn(gameId: GameId)(
      action: SubscriptionRef[SessionState] => IO[GameError, Unit]
  ): IO[StatusException, StateReply] =
    (for
      ref <- sessions.get(gameId)
      _   <- action(ref)
      out <- replyFor(gameId, ref)
    yield out).mapError(GrpcMappers.toStatusException)

  private def replyFor(
      gameId: GameId,
      ref: SubscriptionRef[SessionState]
  ): UIO[StateReply] =
    ref.get.flatMap(GrpcMappers.toStateReply(gameId, _))

object GrpcServer:
  val layer: URLayer[
    GameService & GameEventProducer & GameSessions & Tracing,
    GrpcServer
  ] =
    ZLayer.fromFunction(GrpcServer(_, _, _, _))

  /** Layer exposing the impl as the public RC gRPC service trait — what
    * zio-grpc's `GenericBindable` derivation looks for. The trait is the
    * context-aware variant (`RCGameService = GGameService[RequestContext,
    * StatusException]`) so each rpc sees the per-call gRPC `Metadata`
    * for trace extraction.
    */
  val asServiceLayer: URLayer[
    GameService & GameEventProducer & GameSessions & Tracing,
    ZioGameService.RCGameService
  ] =
    ZLayer.fromFunction(
      (
          gs: GameService,
          p: GameEventProducer,
          s: GameSessions,
          t: Tracing
      ) => new GrpcServer(gs, p, s, t): ZioGameService.RCGameService
    )
