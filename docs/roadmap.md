# Roadmap

Phases follow the 14-phase lecture plan (Prof. Dr. Marko Boger, HTWG Konstanz). Each phase is designed so that earlier layers require no changes — only new code is added at the integration boundary.

---

## Phase 1 — TUI Chess (current)

**Status:** Complete

Console chess game with full move validation, en passant, ANSI board rendering, and in-memory persistence. 100% test coverage enforced.

**Entry point:** `Main.scala`
**Key gap:** No check/checkmate/castling/promotion — game runs until `quit`.

---

## Phase 2 — Missing Chess Rules

**Goal:** Complete the ruleset so the game can end naturally.

| Rule | Where to add | Status |
|---|---|---|
| Pawn promotion | `Game.applyMove` — validate and replace pawn with chosen piece | Done |
| Check detection | `MoveValidator` — reject moves that leave own king in check | Not started |
| Checkmate / stalemate | `Game.applyMove` — return a terminal `GameState` variant | Not started |
| Castling | `MoveValidator` + `Game.updatedBoard` | Not started |
| Draw conditions | `Game.applyMove` — track move history for 50-move / threefold | Not started |

**Also completed (beyond core rules):**
- Notation parsing refactored into `chess.notation` package with Strategy/Chain-of-Responsibility pattern (`NotationResolver` trait, `CoordinateResolver`, `SanResolver`, `CastlingResolver`)
- SAN serialization (`SanSerializer`) for move display
- Live move log in TUI showing last two moves with color-coded labels

---

## Phase 3 — Parser Combinators / FEN / PGN

**Goal:** Parse and serialize game state using a formal grammar.

- New package `chess.codec`
- Implement **FEN** and **PGN** parsers using `scala-parser-combinators`:
  ```
  "org.scala-lang.modules" %% "scala-parser-combinators" % "2.1.1"
  ```
- Parser class extends `RegexParsers`; public API returns `Either[String, T]`
- Post-parse validation chained via for-comprehension on `Either`
- `JsonGameCodec` (Circe) for JSON serialization of `GameState`
- No changes to `chess.model`, `chess.service`, or `chess.repository`

---

## Phase 4 — HTTP / REST (Akka HTTP)

**Goal:** Expose the game over HTTP so any client can play via REST.

- New package `chess.http`
- HTTP framework: **Akka HTTP** with the high-level Routing DSL
- Implement as a **view layer** (same role as TUI — calls `GameService`, no domain logic)
- URL design: nouns not verbs, plural collection + singular instance
  - `POST   /games`              → `GameService.newGame()`
  - `POST   /games/:id/moves`    → `GameService.makeMove(id, input)`
  - `GET    /games/:id`          → `GameService.getState(id)`
  - `DELETE /games/:id`          → terminate game
- Also add a **module-level REST API** on each SBT module to prepare for Docker IPC (Phase 5)
- This REST API will be used for **Gatling performance testing** in Phase 8
- No changes to `GameService` or any layer below it

---

## Phase 5 — Microservices (SBT Multi-project + Docker)

**Goal:** Extract services into separate sbt modules, each packaged as a Docker container.

- Split into SBT sub-projects:
  - `chess-model` — `chess.model`, `chess.model.rules` (pure domain)
  - `chess-service` — `chess.service`, `chess.repository`
  - `chess-http` — `chess.http` (REST API, Akka HTTP)
  - `chess-tui` — `chess.view` + TUI loop
- Each module packaged as a **Docker** container
- Modules communicate between Docker instances via the REST API introduced in Phase 4
- ZLayer wiring stays identical; SBT module boundaries enforce existing dependency rules

---

## Phase 6 — Persistence (MongoDB + PostgreSQL)

**Goal:** Replace in-memory storage with durable databases.

- `MongoGameRepository` — implements `GameRepository` trait, backed by MongoDB
- `PostgresGameRepository` — implements `GameRepository` trait, backed by PostgreSQL
- Swap in `Main.scala` (or HTTP wiring) by changing one `ZLayer` line
- Both implementations can coexist; choose at startup via config

---

## Phase 7 — Web UI

**Goal:** Browser-based board instead of the TUI.

- New module or view (`chess.view.HtmlBoardView`)
- `GameService` and all layers below are unchanged
- WebSocket support can be added to `chess.http` to push board updates after each move
- View layer must stay strictly separate from transport

---

## Phase 8 — Performance (Gatling + JMH)

**Goal:** Measure and optimize throughput and latency.

- **Gatling** load tests against the REST API introduced in Phase 4
- **JMH** microbenchmarks for hot-path domain logic (move validation, board rendering)
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

**Goal:** Stream board state updates to connected clients in real time.

- Wrap `GameService.makeMove` results in a ZIO / fs2 stream
- `GameService.makeMove` return value is the publishing seam — no service changes needed
- Clients subscribe to a game stream by `GameId`

---

## Phase 11 — Kafka Event Publishing

**Goal:** Publish domain events to a Kafka topic for downstream consumers (analytics, replay, spectator service).

- `GameEvent` is already returned by `makeMove` — callers decide what to do with it
- Add a Kafka producer at the HTTP/WebSocket call site
- `(newState, event)` return from `makeMove` is the integration point
- `GameService` itself remains unchanged

---

## Phase 12 — Spark

**Goal:** Large-scale game data processing and analytics.

- Consume game event data from Kafka (Phase 11)
- Spark jobs for move statistics, opening analysis, player ratings

---

## Phase 13 — Tournament

**Goal:** Multi-game, multi-player tournament logic.

- Tournament bracket management
- Multiple concurrent games with shared leaderboard
- Builds on Phase 9 (Bot) and Phase 11 (Kafka events)

---

## Phase 14 — Final Presentation

**Goal:** Demonstrate the full system end-to-end.
