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

The chosen input is **FEN** (Forsyth–Edwards Notation), since it's the natural import/export format for the REST API in Phase 4 — `POST /games` with a FEN body, `GET /games/:id` returning a FEN inside a JSON `GameStateEnvelope`.

- New package `chess.codec`
- Three implementations of the same `FenParser` trait:
  - `FenParserCombinator` — `scala-parser-combinators` (`RegexParsers`)
  - `FenParserFastParse` — `fastparse` (macro-based combinators)
  - `FenParserRegex` — `scala.util.matching.Regex`, no external library
- All three tokenize into six raw fields and share `FenBuilder` for semantic validation, so the implementations are observationally identical.
- `FenSerializer` is the round-trip counterpart and emits the canonical FEN.
- `inCheck` is recomputed on import via `MoveValidator.isInCheck` so imported positions render correctly.
- Public API originally returned `Either[String, GameState]` per the SA-03 addendum's "return type" rule (the FEN parsers were later migrated to `IO[GameError, GameState]` — see [ADR 005](adr/005-pure-domain-model-zio-at-boundaries.md)).
- No changes to `chess.model`, `chess.service`, or `chess.repository`.

---

## Phase 4 — HTTP / REST

**Status:** Complete (zio-http + Tapir).

**Lecture task:** Develop a REST service using Akka HTTP as a further view layer. Also introduce a module-level REST API for interprocess communication (used in Phase 5 Docker IPC).

- New `gateway` module — the project's REST view layer (same role as TUI: calls `GameService` / `GameController`, no domain logic)
- Built on **zio-http** instead of Akka HTTP (for consistency with the ZIO stack); endpoint contracts described with **Tapir** in the cross-compiled `api` module
- Wire DTOs (`BoardStateDto`, `MoveRequest`, `LoadRequest`, …) live in `api` and are shared by the gateway encoder and the Scala.js web-ui decoder via zio-json — single source of truth for the contract
- Session-scoped endpoints (one in-flight game per process, mirroring the TUI) — **later superseded** by per-game `/api/games/{id}/…` routes in the microservices split (the current gateway holds no game state; see [architecture.md → Gateway HTTP / SSE surface](architecture.md)):
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

**Status:** Complete (re-architected together with Phase 11).

**Lecture task:** Start each microservice using Docker. Then start the entire application using Docker Compose.

- The Phase-5 SBT split introduced the multi-service skeleton (it has since grown to **34 modules** as later phases added persistence, the bot, the lobby, the projections, the tournament bot, and post-game analysis — see [architecture.md](architecture.md) for the full map):
  - `domain` (cross JVM/JS), `api` (cross JVM/JS), `rules`, `codec`, `repositoryApi` — libraries (no Docker)
  - `events` — Kafka event ADT + zio-json codecs (new in this phase)
  - `proto` — generated zio-grpc stubs (new in this phase; `coverageEnabled := false` for generated code)
  - `repository` (svc, port 8091) — legacy CRUD REST surface (`/games/{id}` save/load/delete; exercised by Gatling and health checks, not the live read path); Kafka consumer for the `chess.game-events` topic (write side)
  - `game-service` (svc, gRPC :9000) — authoritative in-memory game state; zio-grpc server; Kafka producer
  - `gateway` (svc, HTTP :8090) — public Tapir REST + SSE + Laminar web-ui static; gRPC client to game-service
  - `tui` — terminal client; now runs interactively against the gateway (`make tui`)
  - `webUi` — Laminar/Scala.js single-page app (embedded into gateway resources)
- The `app` monolith is **deleted**. Its responsibilities are split across `gateway` and `gameService`, each running as its own Docker container. `sbt run` at the root no longer works — use `sbt <svc>/run` or `docker compose up`.
- See [ADR 013](adr/013-deletion-of-app-module-and-sbt-run.md) for the rationale.
- Per-service Docker images use sbt-native-packager's `dockerGroupLayers` to put 3rd-party jars in a separate (cached) layer from project jars — a one-file edit triggers a rebuild of only the small project-jar layer.

---

## Phase 6 — Persistence: Slick (PostgreSQL)

**Status:** Complete.

**Lecture task:** Develop a database layer. Use the DAO pattern to make the interface independent of the used DB. Use Slick as first DB implementation.

- **DAO pattern** in place via the `GameRepository` / `LobbyRepository` traits in `persistence/api` — database-agnostic `save` / `load` / `delete`
- `persistence/postgres` implements both traits with **Slick + HikariCP** (`slick` / `slick-hikaricp` 3.6.1, `postgresql` 42.7.4)
- The backend is selected by `PICHESS_BACKEND=postgres` (read by `BackendConfig`); `PersistenceLayers` in `persistence/runtime` is the single swap point — no service-Main change
- Persistence was generalised well beyond Slick: see Phase 7 for the Mongo / Redis / Cassandra backends and the [db-selection-report](db-selection-report.md) for the cross-backend benchmark

---

## Phase 7 — Persistence: MongoDB + Web UI

**Lecture task (MongoDB):** Use MongoDB to build a second DB implementation using the DAO pattern.

**Status:** Complete — and generalised into a multi-backend persistence layer.

- **Web UI** (`web-ui` module — Scala.js + Laminar):
  - Drag-and-drop, promotion dialog, move log, board flip, undo/redo, draw-claim, FEN/PGN/JSON load and export; plus a **public-lobby browser** (over `LobbyRepository.listPublicActive`) and a read-only **spectator (Watch) view**
  - Live state sync with the TUI via `SubscriptionRef[SessionState]` and the gateway's SSE endpoint — moves in either UI appear instantly in the other
  - Coordinated shutdown via `Promise[Nothing, Unit]` — quit from any surface ends both
  - Pure board-logic helpers extracted into `Logic.scala` so they're unit-testable in plain `zio-test` even though scoverage doesn't instrument Scala.js output
  - Wire DTOs and endpoint contracts shared with the gateway via the cross-compiled `api` module
- **MongoDB:** Done — `persistence/mongo` (MongoDB Scala driver 5.5.1) implements the same DAO traits, selected via `PICHESS_BACKEND=mongo`.
- **Beyond the lecture:** `persistence/redis` (zio-redis) and `persistence/cassandra` (DataStax driver) add two more backends, plus `persistence/cache` (a Redis `CachedGameRepository` decorator over any primary). `persistence/contract` runs one Testcontainers behaviour suite against every backend so they stay observationally equal. `mongo + redis` is the validated production default ([db-selection-report](db-selection-report.md)).

---

## Phase 8 — Performance (Gatling)

**Status:** Complete — and grown into a full performance harness.

**Lecture task:** Generate a Gatling performance test script, optimize the generated script, analyse the report, optimize application code, repeat and show the improvement.

- **Gatling** load tests in the `gatling` module, driven by `make perf` (cross-backend) and `make db-matrix` (backend × cache × workload sweeps)
- **k6** added on top (`make k6`) with three surfaces — browser (Chromium), native gRPC, and direct xk6-kafka producer load
- **JMH** microbenchmarks (`bench` module) for the rules / codec / domain / bot hot paths, plus **async-profiler** attachment to live containers (`make profile-async-cpu`)
- Results are written under `perf-reports/<TS>/` and summarised by `make perf-report`; see [performance.md](performance.md), [perf-experiments.md](perf-experiments.md), and [performance-test-results.md](performance-test-results.md)

---

## Phase 9 — Bot / AI

**Status:** Complete — and the project's largest area of ongoing work.

**Goal:** Add a computer opponent.

- `bot-engine` is a pure engine library: negamax **α-β + transposition table**, quiescence, null-move pruning, SEE ordering, singular + check extensions, and a time-budgeted iterative-deepening mode
- **Hybrid HCE + NNUE evaluation** (Stockfish-distilled net) + weighted-random opening book + a Lichess online 7-piece tablebase oracle the Lichess bot uses for endgames (on by default there; not wired into the core engine) — measured at **≈2350 Elo** against UCI_Elo-anchored Stockfish
- `game-service` embeds the engine for vs-computer play; `bot-lichess` runs it online as [pichess-htwg](https://lichess.org/@/pichess-htwg), and `bot-tournament` can play the same engine in external **NowChess** tournaments (tournament protocol implemented and locally end-to-end tested; see [tournament-integration.md](tournament-integration.md))
- Offline training + the Elo harness live in `bot-train` (Texel tuning, self-play, `TournamentMain`) and the Python `nnue-train/` trainer
- **See [bot.md](bot.md)** for the engine reference and how Elo is correctly measured, and [engine-levers.md](engine-levers.md) for the search/eval A/B history

---

## Phase 10 — Reactive Streams

**Status:** Complete (ZIO Streams instead of Akka Streams).

**Lecture task:** Create a stream with Source, Flow, and Sink. Source can be keyboard, file, website, or data in external DSL form.

- The gateway's **SSE endpoint** (`/api/games/{id}/events`) streams `SubscriptionRef.changes` (Source) through a JSON-encoding `Flow` to the connected browser (Sink) — live multi-client board sync
- The same per-game SSE feed also carries **live spectator presence** — a gateway-owned `SubscriptionRef[Int]` per game (`SpectatorPresence`) streamed as `spectators` events to every viewer; a `?role=spectator` watcher is gated by the lobby's per-game policy (`allowSpectate` + `limit`), and a refusal is delivered as a `spectator-denied` event
- The repository and projection services consume `chess.game-events` as **zio-kafka** streams (Source = topic, Sink = the persistence layer / Neo4j / zio-metrics), with stream-level failures interrupting the service via the supervising scope. `spark-analytics` consumes the same topic as a Spark Structured Streaming source
- `GameService.makeMove` returns `(newState, event)` — the publishing seam the producer stream drains into Kafka

---

## Phase 11 — Kafka Event Publishing

**Status:** Complete.

**Lecture task:** Write a Kafka Producer and Consumer connected to your microservices via your data stream.

- Single topic: `chess.game-events`, partition key = `gameId`, KRaft mode (no Zookeeper).
- Event ADT lives in the new `events` SBT module (`chess.events.GameDomainEvent`): `GameStarted | GameLoaded | MoveMade | Undone | Redone | DrawClaimed | Forfeited | GameEnded`. `GameEnded` is defined in the ADT but **not currently emitted** by game-service — terminal outcomes ride on the `resultingFen` of `MoveMade` / `DrawClaimed` / `Forfeited`. Every event carries a `resultingFen` field — the canonical "what to persist after this event" — so the consumer is type-agnostic.
- Producer: **`game-service`** (`KafkaGameEventProducer`, zio-kafka) publishes after every successful state transition with `acks=all` and idempotence on. The MakeMove rpc returns to the gateway only after the produce future resolves.
- Consumer: **`repository`** (`KafkaGameEventConsumer`) subscribes with consumer group `pichess-repository`, applies each event idempotently via `repo.save(gameId, fen)`. Stream-level failures interrupt the service via the supervising scope.
- Serialization: zio-json over the wire (no schema registry — see [ADR 012](adr/012-zio-json-event-serialization-no-schema-registry.md)).
- Coexists with the legacy REST PUT path on the repository (used by Gatling and ad-hoc curl). Both paths are idempotent.

**Next iteration:** game-service replays the topic on startup to rebuild in-memory state — needed for true restart resilience.

---

## Phase 12 — Architecture Patterns (theoretical)

**Goal:** Understand and evaluate architecture patterns (layered, event-driven, pipeline, microservice, space-based, SOA, service-based). No code deliverable — this phase is conceptual.

---

## Phase 13 — Data Aggregation (Spark)

**Status:** Met with a real Apache Spark Lambda projection (`spark-analytics`).

**Lecture task:** Work with Spark to aggregate data from your application. First read from a file, then connect Spark to Kafka as a stream.

The **`spark-analytics`** module satisfies both halves of the brief and goes well beyond:

- **Batch (read from file):** reads an archived `chess.game-events` dump, aggregates (openings, game length, FEN square-occupancy heatmap, opening→outcome) and writes authoritative views to **Parquet**.
- **Streaming (Spark ↔ Kafka):** consumes `chess.game-events` and **sessionizes** each game with `flatMapGroupsWithState` (the speed layer), plus event-time windowed counts with watermarks; publishes per-game `GameSummary` to `chess.analytics`.
- Built with **zio-spark** (effect-typed Spark) — see [ADR 022](adr/022-spark-analytics-projection.md) for the Scala-3.3/Java-17/`for3Use2_13` compat seam this required.

`analytics-service` consumes `chess.analytics` + raw `chess.game-events` and emits **zio-metrics** (rates, outcomes, ECO opening families, length/duration/think-time histograms, records) to **Prometheus → Grafana** — the monitoring dashboard. This **replaced** the earlier Kafka → ClickHouse projection (ClickHouse dropped). The companion **`opening-service`** still builds an **opening tree in Neo4j** (`(:Position)-[:MOVE {san, count}]->(:Position)`).

All optional via `make stack-<bk> EXTRA=analytics,obs`; see [development.md](development.md) and [architecture.md](architecture.md).

---

## Phase 14 — Final Presentation

**Goal:** Demonstrate the full system end-to-end.
