# ADR 005 — ZIO effects throughout, including domain logic

## Status

Accepted (supersedes earlier decision to keep domain logic pure with Either)

## Context

The original design used `Either[GameError, A]` in domain logic (rules, notation, validators) and ZIO effects only at the service layer and above. `GameServiceLive` bridged the two worlds with `ZIO.fromEither(...)`.

This was reconsidered for three reasons:

1. **Two error-handling styles** coexisted — `Either` below the service layer, `ZIO` above — requiring developers to know where the boundary was.
2. **Bridging boilerplate** — `ZIO.fromEither` calls in `GameServiceLive` added noise with no value.
3. **zio-test eliminates the testability argument** — the main justification for pure `Either` was "no ZIO runtime needed for tests." With zio-test, effectful tests are just as concise as pure ones.

## Decision

Use `IO[GameError, A]` (i.e., `ZIO[Any, GameError, A]`) throughout the entire codebase — from domain validation to HTTP handlers. Migrate all tests from ScalaTest to zio-test.

The typed error channel `GameError` flows from the domain layer all the way to the controller boundary, where it is converted to HTTP responses. No widening to `Throwable` occurs within the application code.

```
Domain (IO[GameError, A]):  MoveValidator → Game → NotationResolver → MoveParser
Service (IO[GameError, A]): GameService → GameRepository
Boundary (catchAll):        WebController converts GameError → HTTP Response
                            Main prints GameError.message to TUI
```

## Consequences

**Benefits:**
- Single error-handling style across the entire codebase. No mental context-switching between `Either` and `ZIO`.
- `ZIO.fromEither` bridges eliminated from `GameServiceLive` — domain functions compose directly in ZIO for-comprehensions.
- Typed error channel (`GameError`) is preserved end-to-end. Errors are exhaustively handled at compile time.
- zio-test provides built-in layer provision, eliminating the `Unsafe.unsafe` + `Runtime.default` boilerplate from service tests.
- Test assertions on failures are cleaner: `.flip` extracts the typed error, `.exit` checks for failure.

**Trade-offs:**
- The domain model now depends on ZIO. It cannot be extracted into a standalone library consumed by non-ZIO applications without carrying ZIO as a transitive dependency.
- Pure helper functions (e.g., path-checking, geometry classification) remain pure — only functions that participate in the validation chain or depend on effectful code return `IO`.
- `SanSerializer.toSan` became effectful because its disambiguation logic calls `MoveValidator.validate`, which now returns `IO`. This propagated the effect through callers in `Main` and `WebController`.
