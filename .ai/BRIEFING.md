# ai/BRIEFING.md

Instructions for AI Coding Agents when working on this project.

---

## Project

SoftArchess is a console chess game written in pure Scala 3 with ZIO. It is a university lecture project — each phase introduces a new technology on top of the existing codebase.

See [`docs/architecture.md`](docs/architecture.md) for the full layer structure and package overview.

---

## Code Style

- **Pure Scala only** — no other languages.
- **Functional style** — immutable data, pure functions, no mutation, no side effects outside of ZIO.
- **`val` over `var`** everywhere.
- **No `null`** — use `Option`, `Either`, or other sum types instead.
- **Error handling** — use `scala.util.Try` and its combinators (`map`, `flatMap`, `recover`) instead of try-catch blocks.

---

## Workflow

Follow TDD strictly, in this order:

1. **Plan** — think through the change, ask clarifying questions if needed.
2. **Write tests first** — tests before any implementation.
3. **Implement** — minimum code to make tests pass.
4. **Format** — run `sbt scalafmtAll` after every change to `.scala` files.
5. **Verify coverage** — run `sbt coverage test coverageReport`. Coverage must be 100%; the build fails below that.

---

## Docs

Always keep `docs/` in sync with the code. After any change:

| Change type | File to update |
|---|---|
| New/removed package, layer, or service | [`docs/architecture.md`](docs/architecture.md) |
| New/changed chess rule | [`docs/game-rules.md`](docs/game-rules.md) |
| New sbt command, workflow step, or extension pattern | [`docs/development.md`](docs/development.md) |
| Phase completed or plan changed | [`docs/roadmap.md`](docs/roadmap.md) |
| Significant new design decision | New ADR in [`docs/adr/`](docs/adr/) |

---

## Future Phases

These are known upcoming lecture phases. When suggesting any architectural change, check that it does not close off or complicate a future phase. Prefer thin traits, ZLayer wiring, and pure functions in the domain.

| Phase | Technology | Key integration point |
|---|---|---|
| 3 — Export/Import | JSON (Circe), PGN, FEN, parser combinators | New `chess.codec` package; no domain changes |
| 4 — HTTP | http4s + fs2 | `GameService` trait is the seam; HTTP routes call it directly |
| 5 — Microservices | Separate deployable services | Small, focused interfaces; ZLayer wiring stays the same |
| 6 — Persistence | MongoDB + PostgreSQL | `GameRepository` trait is already in place; new impls swap via ZLayer |
| 7 — Web UI | Browser-based UI | View layer must stay separate from transport |
| 8 — Performance | Gatling + JMH | Avoid hidden allocations or blocking in hot paths |
| 9 — Reactive Streams | fs2 / ZIO streams | `GameService.makeMove` return value is the stream publishing seam |
| 10 — Kafka | Kafka event publishing | `(newState, event)` return from `makeMove` is the integration point |
| 11 — Spark | Large-scale game data processing | — |
| 12 — Tournament | Multi-game, multi-player logic | — |
| 14 — Final presentation | — | — |
