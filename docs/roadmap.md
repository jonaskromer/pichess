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

**Status:** Complete

**Goal:** Apply functional patterns (Option, Either/ZIO errors, for-comprehension, two-track pattern) and complete the ruleset so the game can end naturally.

| Rule | Where to add | Status |
|---|---|---|
| Pawn promotion | `Game.applyMove` — validate and replace pawn with chosen piece | Done |
| Check detection | `MoveValidator` — reject moves that leave own king in check; checked king highlighted in TUI and GUI | Done |
| Checkmate | `Game.applyMove` — game-over guard + checkmate detection via `MoveValidator.hasLegalMove`; `GameStatus` enum (`Playing`, `Checkmate(winner)`, `Draw(reason)`) | Done |
| Stalemate | `Game.applyMove` — detect no legal moves when not in check; `Draw(Stalemate)` | Done |
| Castling | `MoveValidator` (path clear, rights exist, no check/attacked squares) + `Game.updatedBoard` (king+rook movement, rights tracking) + `CastlingResolver` (O-O / O-O-O parsing) | Done |
| 50-move rule | `Game.applyMove` — halfmove clock tracking; `GameController.claimDraw` command | Done |
| Insufficient material | `Game.applyMove` — automatic draw when only kings remain (or K+B, K+N, K+B vs K+B same color) | Done |
| Threefold repetition | `GameController.claimDraw` — claim-based; position key comparison via first 4 FEN fields | Done |
| Fivefold repetition | `GameController.makeMove` — automatic draw when position occurs 5 times | Done |

**Also completed (beyond core rules):**
- Notation parsing refactored into `chess.notation` package with Strategy/Chain-of-Responsibility pattern (`NotationResolver` trait, `CoordinateResolver`, `SanResolver`, `CastlingResolver`)
- SAN serialization (`SanSerializer`) for move display, including `deriveMoveLog` for replaying move history
- Live move log in TUI showing last two moves with color-coded labels
- ZIO typed error channel (`IO[GameError, A]`) throughout — two-track pattern via ZIO's error channel
- Undo/redo support via `GameSnapshot` state history stack (O(1) undo/redo, no replay needed)

---

## Phase 3 — Parser Combinators

**Lecture task (SA-03):** Build **three** parsers for the same input, each using a different library / technique: scala-parser-combinators, fastparse, and plain regex. Public API returns `Either[String, T]`.

**Status:** Complete. JSON and PGN codecs were added in the same package alongside the three FEN parsers — see [ADR 009](adr/009-recompute-derived-state-on-import.md) for the import-validation strategy shared across all formats.

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

**Status:** Complete (zio-http + Tapir).

**Lecture task:** Develop a REST service using Akka HTTP as a further view layer. Also introduce a module-level REST API for interprocess communication (used in Phase 5 Docker IPC).

- New `gateway` module — the project's REST view layer (same role as TUI: calls `GameService` / `GameController`, no domain logic)
- Built on **zio-http** instead of Akka HTTP (for consistency with the ZIO stack); endpoint contracts described with **Tapir** in the cross-compiled `api` module
- Wire DTOs (`BoardStateDto`, `MoveRequest`, `LoadRequest`, …) live in `api` and are shared by the gateway encoder and the Scala.js web-ui decoder via zio-json — single source of truth for the contract
- Session-scoped endpoints (one in-flight game per process, mirroring the TUI):
  - `GET  /api/state`              → current `BoardStateDto`
  - `POST /api/move`               → apply a move (coordinate or SAN)
  - `POST /api/undo` / `/api/redo` → reverse / replay the last half-move
  - `POST /api/draw`               → claim a 50-move / threefold-repetition draw
  - `POST /api/new`                → reset to the starting position
  - `POST /api/load`               → import FEN / PGN / JSON (auto-detected)
  - `GET  /api/export/:format`     → serialize the current position
  - `POST /api/quit`               → trigger shutdown
  - `GET  /api/events`             → SSE stream over `SubscriptionRef.changes` (raw zio-http; doesn't fit Tapir's typed model)
  - `GET  /docs`                   → Tapir-generated Swagger UI
- The `gateway` also serves the web UI's static assets (`/`, `/main.js`) — Scala.js output is copied into managed resources during `sbt compile` so a single classpath powers both API and UI
- `GameService` and the layers below it are unchanged — addition only

---

## Phase 5 — Microservices (SBT Multi-project + Docker)

**Status:** Complete.

**Lecture task:** Start each microservice using Docker. Then start the entire application using Docker Compose.

- SBT split is more granular than the original sketch — 13 sub-projects total:
  - `domain` (cross-compiled JVM/JS) — `chess.model` (board, pieces, errors)
  - `rules` — `chess.model.rules` (move validation, game progression)
  - `codec` — FEN/PGN/JSON parsers, SAN serializer, parser-combinator showcases
  - `api` (cross-compiled JVM/JS) — wire DTOs shared by gateway encoder and web-ui decoder
  - `repository-api` — Tapir endpoint contract for the repository microservice
  - `repository` — `GameRepository` impls (`InMemoryGameRepository`, `HttpGameRepository`) plus `RepositoryServer` (HTTP host)
  - `game-service` — `GameService` orchestration on top of the repo
  - `gateway` — REST/SSE view layer (Phase 4)
  - `tui` — terminal view + controller
  - `web-ui` — Laminar/Scala.js single-page app (Phase 7)
  - `app` — composition root that wires TUI + gateway in one process
- Two Docker-packaged microservices: `app` (port 8090, full TUI+REST+UI) and `repository` (port 8091, standalone state store)
- `docker-compose.yml` runs both; `app` talks to `repository` over REST when `REPOSITORY_URL` is set, otherwise falls back to `InMemoryGameRepository` for local dev
- Cross-service communication uses the typed Tapir client `HttpGameRepository` (errors map to `GameError.InfrastructureError` for retry policies)
- ZLayer wiring is preserved; SBT module boundaries enforce the existing layering rules

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

**Status:** Web UI complete (built ahead of schedule and now feature-equivalent to the TUI). MongoDB not started.

- **Web UI** (`web-ui` module — Scala.js + Laminar):
  - Drag-and-drop, promotion dialog, move log, board flip, undo/redo, draw-claim, FEN/PGN/JSON load and export
  - Live state sync with the TUI via `SubscriptionRef[SessionState]` and the gateway's SSE endpoint — moves in either UI appear instantly in the other
  - Coordinated shutdown via `Promise[Nothing, Unit]` — quit from any surface ends both
  - Pure board-logic helpers extracted into `Logic.scala` so they're unit-testable in plain `zio-test` even though scoverage doesn't instrument Scala.js output
  - Wire DTOs and endpoint contracts shared with the gateway via the cross-compiled `api` module
- **MongoDB:** Pending. Plan: `MongoGameRepository` using the MongoDB Scala driver, swappable via ZLayer alongside the Slick implementation from Phase 6

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
