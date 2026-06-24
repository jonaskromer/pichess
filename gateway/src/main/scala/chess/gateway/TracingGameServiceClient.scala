package chess.gateway

import io.grpc.{Metadata, StatusException}
import io.opentelemetry.api.trace.SpanKind
import pichess.game_service.{
  ActiveGamesReply,
  ExportReply,
  ExportRequest,
  GameIdRequest,
  ListActiveGamesRequest,
  LoadGameRequest,
  MoveRequest,
  NewGameRequest,
  StateReply,
  ZioGameService
}
import scalapb.zio_grpc.ClientTransform
import zio.*
import zio.stream.Stream
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

/** Decorator over `ZioGameService.GameServiceClient` that
  *
  *   1. starts a CLIENT span per rpc, named `GameService/<rpc>`, so the
  *      gateway-side leg of every game-service hop is visible in Jaeger with
  *      proper timing. 2. injects the W3C `traceparent` / `tracestate` of that
  *      span into the outgoing gRPC `Metadata` via `mapMetadataZIO`, so the
  *      game-service's `GrpcServer.serverSpan` can pick it up as the parent of
  *      its own SERVER span — giving us one continuous trace from the public
  *      HTTP entry, through the gRPC hop, into DB calls.
  *
  * `mapMetadataZIO` runs the injector once per call. Because the call is
  * initiated from inside `tracing.span(...)`, the fiber's OTel context already
  * carries the CLIENT span when the injector reads it, so the propagator writes
  * that span's id as `traceparent`.
  *
  * Implements `GameServiceClient` directly (not the simpler `GameService`
  * trait) so existing call sites that wire the generated client type don't need
  * to be updated. `transform` rewraps with the same tracing decorator so
  * chained transformations preserve tracing.
  */
final class TracingGameServiceClient(
    underlying: ZioGameService.GameServiceClient,
    tracing: Tracing
) extends ZioGameService.GameServiceClient:

  private val tracedUnderlying: ZioGameService.GameServiceClient =
    underlying.mapMetadataZIO { safe =>
      safe
        .wrapZIO { md =>
          val carrier = metadataCarrier(md)
          tracing.injectSpan(TraceContextPropagator.default, carrier)
        }
        .as(safe)
    }

  def newGame(request: NewGameRequest): IO[StatusException, StateReply] =
    clientSpan("GameService/newGame")(tracedUnderlying.newGame(request))

  def loadGame(request: LoadGameRequest): IO[StatusException, StateReply] =
    clientSpan("GameService/loadGame")(tracedUnderlying.loadGame(request))

  def makeMove(request: MoveRequest): IO[StatusException, StateReply] =
    clientSpan("GameService/makeMove")(tracedUnderlying.makeMove(request))

  def undo(request: GameIdRequest): IO[StatusException, StateReply] =
    clientSpan("GameService/undo")(tracedUnderlying.undo(request))

  def redo(request: GameIdRequest): IO[StatusException, StateReply] =
    clientSpan("GameService/redo")(tracedUnderlying.redo(request))

  def claimDraw(request: GameIdRequest): IO[StatusException, StateReply] =
    clientSpan("GameService/claimDraw")(tracedUnderlying.claimDraw(request))

  def forfeit(request: GameIdRequest): IO[StatusException, StateReply] =
    clientSpan("GameService/forfeit")(tracedUnderlying.forfeit(request))

  def getState(request: GameIdRequest): IO[StatusException, StateReply] =
    clientSpan("GameService/getState")(tracedUnderlying.getState(request))

  def exportGame(request: ExportRequest): IO[StatusException, ExportReply] =
    clientSpan("GameService/exportGame")(tracedUnderlying.exportGame(request))

  def listActiveGames(
      request: ListActiveGamesRequest
  ): IO[StatusException, ActiveGamesReply] =
    clientSpan("GameService/listActiveGames")(
      tracedUnderlying.listActiveGames(request)
    )

  def subscribeGame(
      request: GameIdRequest
  ): Stream[StatusException, StateReply] =
    // Streaming rpc — span lifecycle would need separate handling
    // (start at subscribe, end at stream completion). Skipping the
    // wrapping span since the SSE bridge is long-lived; child spans
    // from inside the subscriber would still parent to the upstream
    // HTTP SERVER span via the FiberRef-based context.
    tracedUnderlying.subscribeGame(request)

  /** With-response-metadata variant: delegate to the underlying without adding
    * spans (rarely used; if needed we'd write a parallel tracing decorator over
    * `GameServiceClientWithResponseMetadata`).
    */
  def withResponseMetadata
      : ZioGameService.GameServiceClientWithResponseMetadata =
    underlying.withResponseMetadata

  /** Apply a further transform on top of the underlying, preserving the tracing
    * decoration. Used by callers of `withCallOptions`, `withTimeout`, etc.,
    * which forward via `transform` under the hood.
    */
  override def transform(
      t: ClientTransform
  ): ZioGameService.GameServiceClient =
    new TracingGameServiceClient(underlying.transform(t), tracing)

  private def clientSpan[A](name: String)(
      io: => IO[StatusException, A]
  ): IO[StatusException, A] =
    tracing.span(name, SpanKind.CLIENT)(io)

  // `private[gateway]` rather than `private` so the unit test in
  // `TracingGameServiceClientSpec` can exercise the `set` body directly.
  // The branch only fires when OpenTelemetry actually has a span
  // context to propagate; with `TracingLayer.noop` in tests we never
  // get that for free, so the dedicated unit test pins it down.
  private[gateway] def metadataCarrier(
      md: Metadata
  ): OutgoingContextCarrier[Metadata] =
    new OutgoingContextCarrier[Metadata]:
      override val kernel: Metadata = md
      override def set(carrier: Metadata, key: String, value: String): Unit =
        carrier.put(
          Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER),
          value
        )

object TracingGameServiceClient:

  /** Layer that wraps the existing raw `GameServiceClient` with the tracing
    * decorator. Wire alongside the raw client layer; the decorator replaces the
    * raw client in the environment.
    */
  val layer: URLayer[
    ZioGameService.GameServiceClient & Tracing,
    ZioGameService.GameServiceClient
  ] =
    ZLayer.fromFunction { (raw: ZioGameService.GameServiceClient, t: Tracing) =>
      new TracingGameServiceClient(raw, t): ZioGameService.GameServiceClient
    }
