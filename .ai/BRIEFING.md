# ai/BRIEFING.md

Instructions for AI Coding Agents when working on this project.

---

## Project

πChess is a console chess game written in pure Scala 3 with ZIO. It is a university lecture project — each phase introduces a new technology on top of the existing codebase.

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

The in-game help screen (`src/main/scala/chess/view/HelpView.scala`) must also be kept in sync:

| Change type | Update HelpView when… |
|---|---|
| New command added to `Main.scala` | Add it to the COMMANDS section |
| Chess rule implemented | Move it from NOT YET IMPLEMENTED to IMPLEMENTED RULES |
| Move notation changed | Update the MOVE NOTATION section |

---

## Required Reading — Addendums

The following addendum files are **required reading** for any AI agent working on this project. They contain the full academic context for each lecture phase, including task requirements, design constraints, and technology choices that must be followed.

| Addendum | Covers |
|----------|--------|
| [`addendum-sa01-intro-architecture.md`](addendum-sa01-intro-architecture.md) | Architecture philosophy, 14-phase plan, tooling, architect role |
| [`addendum-sa02-functional-style.md`](addendum-sa02-functional-style.md) | ScalaChess domain model, functional style rules, monads, two-track pattern |
| [`addendum-sa03-parser-combinators.md`](addendum-sa03-parser-combinators.md) | Parser combinator operators, Either return pattern, FEN/PGN parser task |
| [`addendum-sa04-microservices.md`](addendum-sa04-microservices.md) | 9 microservice characteristics, SBT multi-project, Docker, Task 3 |
| [`addendum-sa05-rest.md`](addendum-sa05-rest.md) | REST principles, URL design rules, Akka HTTP routing DSL, Task 4 |

---

## Future Phases

These are known upcoming lecture phases. When suggesting any architectural change, check that it does not close off or complicate a future phase. Prefer thin traits, ZLayer wiring, and pure functions in the domain.

| Phase | Technology | Key integration point |
|---|---|---|
| 3 — Export/Import | JSON (Circe), PGN, FEN, parser combinators | New `chess.codec` package; no domain changes |
| 4 — HTTP | **Akka HTTP** (Routing DSL) | `GameService` trait is the seam; HTTP routes call it directly; also add module REST API for Docker IPC |
| 5 — Microservices | SBT multi-project + **Docker** | Each module in its own container; modules communicate via the Phase 4 REST API |
| 6 — Persistence | MongoDB + PostgreSQL | `GameRepository` trait is already in place; new impls swap via ZLayer |
| 7 — Web UI | Browser-based UI | View layer must stay separate from transport |
| 8 — Performance | Gatling + JMH | Avoid hidden allocations or blocking in hot paths |
| 9 — Bot / AI | Pluggable move strategy | Bot calls `GameService.makeMove` with computed move |
| 10 — Reactive Streams | fs2 / ZIO streams | `GameService.makeMove` return value is the stream publishing seam |
| 11 — Kafka | Kafka event publishing | `(newState, event)` return from `makeMove` is the integration point |
| 12 — Spark | Large-scale game data processing | Consume Kafka events from Phase 11 |
| 13 — Tournament | Multi-game, multi-player logic | Builds on Bot (Phase 9) + Kafka (Phase 11) |
| 14 — Final presentation | — | — |
