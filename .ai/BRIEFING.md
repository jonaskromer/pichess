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
- **Error handling** — use `ZIO`'s typed error channel (`IO[GameError, A]`) throughout the codebase. Domain errors use the `GameError` enum. No try-catch blocks.

---

## Workflow

Follow TDD strictly, in this order:

1. **Plan** — think through the change, ask clarifying questions if needed.
2. **Write tests first** — tests before any implementation.
3. **Implement** — minimum code to make tests pass.
4. **Format** — run `sbt scalafmtAll` after every change to `.scala` files.
5. **Verify coverage** — run `sbt coverage test coverageReport`. Coverage must be 100%; the build fails below that.
6. **Diagnose coverage gaps** — if coverage is below 100%, run `python3 scripts/check-coverage.py` to see exactly which files and lines are uncovered.

### Bug fixes — reproducer-first

When fixing a bug, you **must** write a regression spec **before** touching the
production code. This is non-negotiable:

1. **Write a failing spec that reproduces the bug.** Name the test
   `regression: <one-line summary>` and add a comment above it describing the
   symptom and (if known) the commit or issue that introduced the bug.
2. **Run the spec and verify it fails in the expected way** — the right
   assertion must be the one that fails, with the right error message. A spec
   that fails for the wrong reason is not a reproducer; rewrite it until the
   failure mode matches the actual bug.
3. **Only then fix the bug.** The regression spec going green is the
   definition of "fixed".
4. **Never delete a `regression:` test.** If a future refactor changes the API
   it touches, *adapt* the test to the new shape — the historical bug it
   guards against is still real. The only valid reason to delete a regression
   test is that the underlying logic no longer exists at all (e.g. an entire
   subsystem has been removed). If you do remove one, justify it in the commit
   message.

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
| [`addendum-sa03-parser-combinators.md`](addendum-sa03-parser-combinators.md) | Parser combinator operators, Either return pattern, three-parser task (combinators / fastparse / regex) |
| [`addendum-sa04-microservices.md`](addendum-sa04-microservices.md) | 9 microservice characteristics, SBT multi-project, Docker, Task 3 |
| [`addendum-sa05-rest.md`](addendum-sa05-rest.md) | REST principles, URL design rules, Akka HTTP routing DSL, Task 4 |
| [`addendum-sa06-docker.md`](addendum-sa06-docker.md) | Docker containerization, Dockerfile, docker-compose |
| [`addendum-sa07-slick.md`](addendum-sa07-slick.md) | Slick (PostgreSQL), functional relational mapping |
| [`addendum-sa08-mongodb.md`](addendum-sa08-mongodb.md) | MongoDB document store, Scala driver |
| [`addendum-sa09-performance-testing.md`](addendum-sa09-performance-testing.md) | Gatling load tests, JMH microbenchmarks |
| [`addendum-sa10-reactive-streams.md`](addendum-sa10-reactive-streams.md) | Reactive streams, backpressure, Akka Streams / ZIO Streams |
| [`addendum-sa11-kafka.md`](addendum-sa11-kafka.md) | Kafka event publishing, producer/consumer |
| [`addendum-sa12-architecture-patterns.md`](addendum-sa12-architecture-patterns.md) | Architecture patterns (CQRS, event sourcing, etc.) |
| [`addendum-sa13-spark.md`](addendum-sa13-spark.md) | Apache Spark, batch processing |

---

## Multi-Agent Workflow

This project supports multi-agent coding. Agent role definitions live in [`.ai/agents/`](agents/). See [`agents/README.md`](agents/README.md) for the full lineup and intended workflow order.

When working as part of a multi-agent setup, each agent should read its own role file and follow the scope/constraints defined there. When working as a single agent, follow the TDD workflow below as before.

---

## Future Phases

These are known upcoming lecture phases. When suggesting any architectural change, check that it does not close off or complicate a future phase. Prefer thin traits, ZLayer wiring, and pure functions in the domain.

| Phase | Lecture technology | Key integration point |
|---|---|---|
| 3 — Parsers (combinators / fastparse / regex) | `scala-parser-combinators`, `fastparse`, `scala.util.matching.Regex` | `chess.codec` package; FEN parsers + serializer; no domain changes |
| 4 — HTTP / REST | **Akka HTTP** Routing DSL (or zio-http) | `GameService` trait is the seam; HTTP routes call it directly; also add module REST API for Docker IPC |
| 5 — Microservices + Docker | SBT multi-project + **Docker Compose** | Each module in its own container; modules communicate via Phase 4 REST API |
| 6 — Slick (PostgreSQL) | **Slick** FRM + DAO pattern | `GameRepository` trait is already the DAO; new Slick impl swaps via ZLayer |
| 7 — MongoDB + Web UI | **MongoDB** Scala driver | Second `GameRepository` impl behind same DAO trait. Web UI already partially complete. |
| 8 — Performance | **Gatling** (record, optimize, measure, improve) | REST API (Phase 4) is the Gatling target |
| 9 — Bot / AI | Pluggable move strategy | Bot calls `GameService.makeMove` with computed move |
| 10 — Reactive Streams | **Akka Streams** GraphDSL (or ZIO Streams) | `GameService.makeMove` return value is the stream publishing seam |
| 11 — Kafka | **Alpakka Kafka** (or ZIO Kafka) | `(newState, event)` return from `makeMove` is the integration point; connect to Phase 10 stream |
| 12 — Architecture Patterns | Theoretical (no code) | Evaluate: layered, event-driven, microservice, space-based, SOA |
| 13 — Spark | **Apache Spark** + Kafka stream | Aggregate game data from Kafka; Spark requires Scala 2.12 sub-project |
| 14 — Final presentation | — | — |
