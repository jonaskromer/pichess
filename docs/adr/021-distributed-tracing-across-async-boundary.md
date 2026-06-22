# ADR 021 — Distributed tracing across the async boundary

## Status

Accepted

## Context

Across a gateway, game-service, Kafka, and a repository, "why was this move slow,
and where did it fail?" is unanswerable from per-service logs alone. We wanted one
trace per request spanning HTTP, gRPC and — the hard part — the **async Kafka
hop**, where most setups give up and the causal chain breaks.

## Decision

Wire OpenTelemetry as **cross-cutting decorators** at every boundary, propagating
the W3C `traceparent` end to end.

- **Deps:** `zio-opentelemetry` + OTel SDK + `opentelemetry-exporter-otlp`
  (`build.sbt:833-835`).
- **HTTP in:** `TracingMiddleware.serverSpan` extracts the incoming `traceparent`
  into a SERVER span (`observability/.../TracingMiddleware.scala:40,51`).
- **gRPC out/in:** `TracingGameServiceClient` injects the context into gRPC
  `Metadata` (`gateway/.../TracingGameServiceClient.scala:48-54`); `GrpcServer`
  extracts it on the server (`game-service/.../GrpcServer.scala:243-250`).
- **Across Kafka (the key hop):** `KafkaGameEventProducer` injects `traceparent`
  into record **headers** before producing
  (`game-service/.../events/KafkaGameEventProducer.scala:39,69-80`);
  `KafkaGameEventConsumer` in the repository extracts it from headers and
  continues the trace (`repository/.../KafkaGameEventConsumer.scala:66-71,100`).
  Both ends use the same `TraceContextPropagator.default`, so the trace survives
  the async hop.
- **DB + lobby proxy:** `TracedGameRepository`/`TracedLobbyRepository` wrap each
  call in an INTERNAL span (`persistence/runtime/...`); `LobbyProxy` injects
  `traceparent` into the forwarded HTTP lobby request
  (`gateway/.../LobbyProxy.scala:102,106`).

Result: one trace spans **gateway HTTP → gRPC → game-service → Kafka →
repository write** (plus the parallel gateway → lobby-service HTTP leg).

## Consequences

**Benefits:**
- A single trace id crosses three transports including the async Kafka boundary,
  so a downstream projection or repository write is causally linked back to the
  originating HTTP request.
- Tracing is a decorator concern, added at the seams (middleware, client wrapper,
  repo decorator), not threaded through business logic.

**Trade-offs:**
- Instrumentation is pervasive — every boundary needs its inject/extract carrier,
  which is real surface to maintain.
- The tracing rides on `zio-opentelemetry 3.0.0-RC24` (`build.sbt:54`), a
  release-candidate dependency.
- Coverage isn't total: the `SubscribeGame` server-stream is intentionally left
  un-spanned — a documented gap.

Alternatives rejected: per-service logs/metrics only (no cross-service
causality); **dropping the trace at the Kafka boundary** (the common shortcut —
it loses exactly the async link we most needed).
