# Roadmap

Phases follow the 14-phase lecture plan (Prof. Dr. Marko Boger, HTWG Konstanz). Each phase is designed so that earlier layers require no changes — only new code is added at the integration boundary.

> **Note:** The lecture specifies certain technologies (Akka HTTP, Slick, Akka Streams). Where this project has chosen ZIO equivalents (zio-http, ZIO JDBC, ZIO Streams), the deviation is noted. The architectural patterns and layer structure match the lecture requirements.

---

## Phase 1 — TUI Chess

**Status:** Complete

Console chess game with full move validation, en passant, pawn promotion, ANSI board rendering, and in-memory persistence. 100% test coverage enforced.

**Entry point:** `Main.scala`

---

## Phase 2 — Functional Style / Missing Chess Rules

**Status:** Partially complete (functional style done; some chess rules remain)

**Goal:** Apply functional patterns (Option, Either/ZIO errors, for-comprehension, two-track pattern) and complete the ruleset so the game can end naturally.

| Rule | Where to add | Status |
|---|---|---|
| Pawn promotion | `Game.applyMove` — validate and replace pawn with chosen piece | Done |
| Check detection | `MoveValidator` — reject moves that leave own king in check; checked king highlighted in TUI and GUI | Done |
| Checkmate | `Game.applyMove` — game-over guard + checkmate detection via `MoveValidator.hasLegalMove`; `GameStatus` enum (`Playing`, `Checkmate(winner)`) | Done |
| Stalemate | `Game.applyMove` — detect no legal moves when not in check | Not started |
| Castling | `MoveValidator` (path clear, rights exist, no check/attacked squares) + `Game.updatedBoard` (king+rook movement, rights tracking) + `CastlingResolver` (O-O / O-O-O parsing) | Done |
| Draw conditions | `Game.applyMove` — track move history for 50-move / threefold | Not started |

**Also completed (beyond core rules):**
- Notation parsing refactored into `chess.notation` package with Strategy/Chain-of-Responsibility pattern (`NotationResolver` trait, `CoordinateResolver`, `SanResolver`, `CastlingResolver`)
- SAN serialization (`SanSerializer`) for move display
- Live move log in TUI showing last two moves with color-coded labels
- ZIO typed error channel (`IO[GameError, A]`) throughout — two-track pattern via ZIO's error channel

---

## Phase 3 — Parser Combinators (current)

**Lecture task (SA-03):** Build **three** parsers for the same input, each using a different library / technique: scala-parser-combinators, fastparse, and plain regex. Public API returns `Either[String, T]`.

**Status:** Complete.

The chosen input is **FEN** (Forsyth–Edwards Notation), since it's the natural import/export format for the REST API in Phase 4 — `POST /games` with a FEN body, `GET /games/:id` returning a FEN string.

- New package `chess.codec`
- Three implementations of the same `FenParser` trait:
  - `FenParserCombinator` — `scala-parser-combinators` (`RegexParsers`)
  - `FenParserFastParse` — `fastparse` (macro-based combinators)
  - `FenParserRegex` — `scala.util.matching.Regex`, no external library
- All three tokenize into six raw fields and share `FenBuilder` for semantic validation, so the implementations are observationally identical.
- `FenSerializer` is the round-trip counterpart and emits the canonical FEN.
- `inCheck` is recomputed on import via `MoveValidator.isInCheck` so imported positions render correctly.
- Public API returns `Either[String, GameState]` per the SA-03 addendum's "return type" rule.
- No changes to `chess.model`, `chess.service`, or `chess.repository`.

---

## Phase 4 — HTTP / REST

**Lecture task:** Develop a REST service using Akka HTTP as a further view layer. Also introduce a module-level REST API for interprocess communication (used in Phase 5 Docker IPC).

- New package `chess.http`
- Lecture specifies **Akka HTTP** with the high-level Routing DSL; project may use **zio-http** for consistency with ZIO stack
- Implement as a **view layer** (same role as TUI — calls `GameService`, no domain logic)
- URL design: nouns not verbs, plural collection + singular instance
  - `POST   /games`              → `GameService.newGame()`
  - `POST   /games/:id/moves`    → `GameService.makeMove(id, input)`
  - `GET    /games/:id`          → `GameService.getState(id)`
  - `DELETE /games/:id`          → terminate game
- This REST API will be used for **Gatling performance testing** in Phase 8
- No changes to `GameService` or any layer below it

---

## Phase 5 — Microservices (SBT Multi-project + Docker)

**Lecture task:** Start each microservice using Docker. Then start the entire application using Docker Compose.

- Split into SBT sub-projects:
  - `chess-model` — `chess.model`, `chess.model.rules` (domain)
  - `chess-service` — `chess.service`, `chess.repository`
  - `chess-http` — `chess.http` (REST API)
  - `chess-tui` — `chess.view` + TUI loop
- Each module packaged as a **Docker** container with its own `Dockerfile`
- Orchestrate all services with **Docker Compose** (`compose.yaml`)
- Modules communicate between Docker instances via the REST API introduced in Phase 4
- ZLayer wiring stays identical; SBT module boundaries enforce existing dependency rules

---

## Phase 6 — Persistence: Slick (PostgreSQL)

**Lecture task:** Develop a database layer. Use the DAO pattern to make the interface independent of the used DB. Use Slick as first DB implementation.

- **DAO pattern** already in place via `GameRepository` trait — database-agnostic interface with `save`, `load`, `delete`
- Implement `SlickGameRepository` (or `PostgresGameRepository`) using **Slick** (Functional-Relational Mapping)
- Slick dependency: `"com.typesafe.slick" %% "slick" % "3.x"`
- Define persistent entity classes separate from domain model (e.g., `PersistentGameState`)
- Swap in `Main.scala` by changing one `ZLayer` line

---

## Phase 7 — Persistence: MongoDB + Web UI

**Lecture task (MongoDB):** Use MongoDB to build a second DB implementation using the DAO pattern.

**Status:** Web UI partially complete (done ahead of schedule). MongoDB not started.

- **MongoDB:** Implement `MongoGameRepository` using the MongoDB Scala driver. Swap via ZLayer alongside the Slick implementation.
- **Web UI** (implemented): browser GUI served via zio-http with drag-and-drop, promotion dialog, and move log
- TUI and web GUI share state via `SubscriptionRef[SessionState]` — moves in either UI are instantly visible in the other
- State changes pushed to the browser via **Server-Sent Events** (SSE); TUI races `readLine` against `session.changes`
- Coordinated shutdown via `Promise[Nothing, Unit]` — quit from either UI triggers goodbye in both
- `GameController.makeMove` encapsulates shared move-processing logic used by both UIs
- View files: `HtmlPage` (HTML/CSS/JS), `WebBoardView` (JSON serialization)

---

## Phase 8 — Performance (Gatling)

**Lecture task:** Generate a Gatling performance test script, optimize the generated script, analyse the report, optimize application code, repeat and show the improvement.

- **Gatling** load tests against the REST API introduced in Phase 4
- Use Gatling Recorder to generate initial simulation script, then optimize by hand
- Performance patterns to consider: Flyweight (chess pieces), Object Pool, Proxy (lazy loading)
- Avoid hidden allocations or blocking calls in hot paths
- `GameService` and domain remain unchanged

---

## Phase 9 — Bot / AI

**Goal:** Add a computer opponent.

- AI player calls `GameService.makeMove` with a computed move
- Move selection strategy is pluggable (random, minimax, etc.)
- No changes to domain or HTTP layer

---

## Phase 10 — Reactive Streams

**Lecture task:** Create a stream with Source, Flow, and Sink. Source can be keyboard, file, website, or data in external DSL form.

- Lecture specifies **Akka Streams** with GraphDSL; project may use **ZIO Streams** for consistency
- Wrap `GameService.makeMove` results in a stream
- `GameService.makeMove` return value is the publishing seam — no service changes needed
- Clients subscribe to a game stream by `GameId`
- SSE endpoint (`/api/events`) already uses `SubscriptionRef.changes` as a ZIO Stream — this is a partial implementation

---

## Phase 11 — Kafka Event Publishing

**Lecture task:** Write a Kafka Producer and Consumer connected to your microservices via your data stream.

- `GameEvent` is already returned by `makeMove` — callers decide what to do with it
- Add a Kafka producer at the HTTP/WebSocket call site, connected via the reactive stream from Phase 10
- Lecture specifies **Alpakka Kafka** (Akka Streams + Kafka connector); project may use ZIO Kafka
- `(newState, event)` return from `makeMove` is the integration point
- `GameService` itself remains unchanged

---

## Phase 12 — Architecture Patterns (theoretical)

**Goal:** Understand and evaluate architecture patterns (layered, event-driven, pipeline, microservice, space-based, SOA, service-based). No code deliverable — this phase is conceptual.

---

## Phase 13 — Spark

**Lecture task:** Work with Spark to aggregate data from your application. First read from a file, then connect Spark to Kafka as a stream.

- Spark dependencies: `spark-core`, `spark-streaming`, `spark-sql`, `spark-streaming-kafka`
- Note: Spark requires Scala 2.12 — may need a separate SBT sub-project with Scala 2.12
- Consume game event data from Kafka (Phase 11)
- Spark jobs for move statistics, opening analysis, player ratings

---

## Phase 14 — Final Presentation

**Goal:** Demonstrate the full system end-to-end.
