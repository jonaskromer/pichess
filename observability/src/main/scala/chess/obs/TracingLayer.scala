package chess.obs

import io.opentelemetry.api.common.{AttributeKey, Attributes as OtelAttributes}
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.api.OpenTelemetry as JOpenTelemetry
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
// `export` is a Scala 3 reserved keyword, hence the backticks on the
// matching java package segment.
import io.opentelemetry.sdk.trace.`export`.BatchSpanProcessor
import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry as ZOpenTelemetry
import zio.telemetry.opentelemetry.context.ContextStorage
import zio.telemetry.opentelemetry.tracing.Tracing

/** Builds an OpenTelemetry SDK + zio-opentelemetry `Tracing` service per
  * microservice. Spans are exported via OTLP gRPC to the
  * `OTEL_EXPORTER_OTLP_ENDPOINT` host (default `http://jaeger:4317`,
  * which is what the `obs` docker-compose profile exposes).
  *
  * The service name (visible in Jaeger's service list) is the per-service
  * `serviceName` parameter; an `OTEL_SERVICE_NAME` env override takes
  * precedence to support multi-instance fan-out.
  *
  * `Tracing` is the entry point services call into. The `ContextStorage`
  * comes from `OpenTelemetry.contextZIO` (FiberRef-backed) so contexts
  * propagate across `fork` / `flatMap` automatically, as a ZIO service
  * caller would expect.
  *
  * The SDK is built scoped — `Scope` finalisers flush pending spans on
  * service shutdown, so a stop-the-world SIGTERM doesn't drop in-flight
  * traces.
  */
object TracingLayer:

  val defaultEndpoint = "http://jaeger:4317"
  val EnvOtlpEndpoint = "OTEL_EXPORTER_OTLP_ENDPOINT"
  val EnvServiceName  = "OTEL_SERVICE_NAME"

  /** Read OTLP endpoint with env override. */
  def endpointFromEnv: UIO[String] =
    zio.System
      .env(EnvOtlpEndpoint)
      .orDie
      .map(_.filter(_.trim.nonEmpty).getOrElse(defaultEndpoint))

  /** Read service name with env override. */
  def serviceNameFromEnv(default: String): UIO[String] =
    zio.System
      .env(EnvServiceName)
      .orDie
      .map(_.filter(_.trim.nonEmpty).getOrElse(default))

  /** Scoped construction of the configured OTel SDK. The
    * `OtlpGrpcSpanExporter` opens a long-lived gRPC channel to the
    * collector; the scope finaliser closes it.
    */
  private def buildSdk(
      serviceName: String,
      endpoint: String,
  ): ZIO[Scope, Throwable, JOpenTelemetry] =
    ZIO.fromAutoCloseable(ZIO.attempt {
      val exporter = OtlpGrpcSpanExporter
        .builder()
        .setEndpoint(endpoint)
        .build()

      val resource = Resource.getDefault.merge(
        Resource.create(
          OtelAttributes
            .builder()
            .put(AttributeKey.stringKey("service.name"), serviceName)
            .build()
        )
      )

      val tracerProvider = SdkTracerProvider
        .builder()
        .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
        .setResource(resource)
        .build()

      OpenTelemetrySdk
        .builder()
        .setTracerProvider(tracerProvider)
        .setPropagators(
          ContextPropagators.create(W3CTraceContextPropagator.getInstance())
        )
        .build()
    })

  /** Drop-in layer providing both `Tracing` and `ContextStorage`. Wire
    * this into each service Main alongside the metrics + profiler
    * layers. `defaultServiceName` is the name shown in Jaeger when
    * `OTEL_SERVICE_NAME` isn't set.
    */
  def live(defaultServiceName: String): TaskLayer[Tracing & ContextStorage] =
    ZLayer.make[Tracing & ContextStorage](
      ZLayer
        .scoped {
          for
            name <- serviceNameFromEnv(defaultServiceName)
            ep   <- endpointFromEnv
            sdk  <- buildSdk(name, ep)
          yield sdk
        },
      ZOpenTelemetry.contextZIO,
      ZOpenTelemetry.tracing(defaultServiceName),
    )

  /** No-op tracing — spans are accepted but never exported. Use when
    * the `obs` compose profile isn't active so the service doesn't hold
    * open a long-lived OTLP gRPC channel to a Jaeger that isn't there.
    */
  val noop: ULayer[Tracing & ContextStorage] =
    ZLayer.make[Tracing & ContextStorage](
      ZOpenTelemetry.noop,
      ZOpenTelemetry.contextZIO,
      ZOpenTelemetry.tracing("noop"),
    )

  /** Returns [[live]] when `TRACING_ENABLED=true`, [[noop]] otherwise.
    * Read synchronously from `sys.env` at boot so the layer choice can
    * be made in a single line at the service Main's provide call. */
  def fromEnv(defaultServiceName: String): TaskLayer[Tracing & ContextStorage] =
    if (sys.env.get("TRACING_ENABLED").exists(_.equalsIgnoreCase("true")))
      live(defaultServiceName)
    else
      noop
