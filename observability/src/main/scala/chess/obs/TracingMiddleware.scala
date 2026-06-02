package chess.obs

import io.opentelemetry.api.trace.SpanKind
import zio.*
import zio.http.*
import zio.telemetry.opentelemetry.context.{ContextStorage, IncomingContextCarrier}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import scala.collection.mutable

/** zio-http middleware that emits one OpenTelemetry SERVER span per
  * inbound request and chains it to any upstream trace context found in
  * the request headers (W3C `traceparent` / `tracestate`).
  *
  * Wire by attaching to the assembled Routes with `routes @@
  * TracingMiddleware.serverSpan`. The `Tracing` and `ContextStorage`
  * services must be provided by [[TracingLayer]] further up the stack.
  *
  * Outgoing-side instrumentation (gRPC client headers, Kafka record
  * headers, DB call wrappers) lives in dedicated wrappers — this
  * middleware covers the public HTTP front door only.
  */
object TracingMiddleware:

  private def carrierForRequest(
      req: Request
  ): IncomingContextCarrier[mutable.Map[String, String]] =
    val initial = mutable.Map.empty[String, String]
    req.headers.foreach { h =>
      initial.update(h.headerName, h.renderedValue)
    }
    IncomingContextCarrier.default(initial)

  /** SERVER-side span wrapper. Span name is `<METHOD> <path>` — the same
    * convention OTel HTTP semantic conventions specify; finer-grained
    * routing-aware naming (e.g. `GET /games/{id}`) is left to per-
    * endpoint instrumentation.
    */
  val serverSpan: HandlerAspect[Tracing & ContextStorage, Unit] =
    HandlerAspect.interceptHandlerStateful[
      Tracing & ContextStorage,
      UIO[Any],
      Unit,
    ](
      Handler.fromFunctionZIO[Request] { req =>
        val carrier  = carrierForRequest(req)
        val spanName = s"${req.method.name} ${req.path.toString}"
        ZIO.serviceWithZIO[Tracing] { tracing =>
          tracing
            .extractSpanUnsafe(
              TraceContextPropagator.default,
              carrier,
              spanName,
              SpanKind.SERVER,
            )
            .map { case (_, finalize) =>
              (finalize, (req, ()))
            }
        }
      }
    )(
      Handler.fromFunctionZIO[(UIO[Any], Response)] { case (finalize, resp) =>
        finalize.as(resp)
      }
    )
