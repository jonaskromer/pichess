# ADR 003 — ZLayer for dependency injection

## Status

Accepted

## Context

The application has stateful components (`GameRepository`, `GameService`) that need to be wired together. Options considered:

1. **Manual DI** — pass dependencies as constructor arguments, wire at `main`.
2. **ZLayer** — ZIO's built-in dependency injection via the `ZLayer` / `ZEnvironment` system.
3. **Third-party DI framework** — e.g. MacWire, Guice.

## Decision

All stateful components are provided as `ZLayer`s. Wiring happens in `Main.scala`:

```scala
app.provide(
  GameService.layer,
  InMemoryGameRepository.layer
)
```

## Consequences

**Benefits:**
- Swapping an implementation (e.g. `InMemoryGameRepository` → `PostgresGameRepository`) requires changing one line in `Main.scala`. No other file is touched.
- The type system enforces the dependency graph at compile time — a missing layer is a compile error, not a runtime crash.
- Test specs can provide mock or in-memory layers without modifying production code.
- Consistent with the ZIO ecosystem already in use for effect management.

**Trade-offs:**
- ZLayer has a learning curve for developers unfamiliar with ZIO.
- The abstraction is heavier than plain constructor injection for a project of this size — accepted as a deliberate investment in the planned future phases ([HTTP](../roadmap.md#phase-4--http-api), [microservices](../roadmap.md#phase-5--microservices), [dual-database](../roadmap.md#phase-6--mongodb--postgresql-persistence)).
