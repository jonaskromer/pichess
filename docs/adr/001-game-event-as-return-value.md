# ADR 001 — GameEvent as a return value, not a side-effect bus

## Status

Accepted

## Context

`GameService.makeMove` needs to communicate domain events (e.g. `MoveMade`) to callers. Two common patterns exist:

1. **Side-effect bus** — the service internally publishes events to a shared event bus or Kafka topic.
2. **Return value** — the service returns the event alongside the new state; callers decide what to do with it.

## Decision

`makeMove` returns `(GameState, GameEvent.MoveMade)`. The caller is responsible for publishing, logging, or ignoring the event.

## Consequences

**Benefits:**
- `GameService` has no dependency on Kafka, an event bus, or any messaging infrastructure.
- The service is trivially testable — tests inspect the returned tuple directly.
- Kafka publishing becomes a call-site concern in the HTTP/WebSocket layer ([Phase 10](../roadmap.md#phase-10--kafka-event-publishing)), added without touching `GameService`.
- Multiple callers (TUI, HTTP, WebSocket) can each decide independently what to do with the event.

**Trade-offs:**
- Callers must explicitly handle (or discard) the event. This is intentional — silent event loss is a bug, not a convenience.
