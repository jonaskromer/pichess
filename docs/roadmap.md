# Roadmap

Phases build on each other. Each phase is designed so that earlier layers require no changes — only new code is added at the integration boundary.

---

## Phase 1 — TUI Chess (current)

**Status:** Complete

Console chess game with full move validation, en passant, ANSI board rendering, and in-memory persistence. 100% test coverage enforced.

**Entry point:** `Main.scala`
**Key gap:** No check/checkmate/promotion — game runs until `quit`.

---

## Phase 2 — Missing Chess Rules

**Goal:** Complete the ruleset so the game can end naturally.

| Rule | Where to add |
|---|---|
| Check detection | `MoveValidator` — reject moves that leave own king in check |
| Checkmate / stalemate | `Game.applyMove` — return a terminal `GameState` variant |
| Castling | `MoveValidator` + `Game.updatedBoard` |
| Pawn promotion | `Game.updatedBoard` — replace pawn with chosen piece |
| Draw conditions | `Game.applyMove` — track move history for 50-move / threefold |

---

## Phase 3 — JSON / PGN Export

**Goal:** Serialize game state and move history for external use.

- New package `chess.codec`
- `JsonGameCodec` — encodes `GameState` as JSON
- `PgnGameCodec` — encodes move history as PGN
- No changes to `chess.model`, `chess.service`, or `chess.repository`

---

## Phase 4 — HTTP API

**Goal:** Expose the game over HTTP so a web or mobile client can play.

- New package `chess.http`
- `GameRoutes` — HTTP routes backed by `GameService` via ZIO
- `POST /games` → `GameService.newGame()`
- `POST /games/:id/moves` → `GameService.makeMove(id, input)`
- `GET /games/:id` → `GameService.getState(id)`
- No changes to `GameService` or any layer below it

---

## Phase 5 — Microservices

**Goal:** Extract services into separate sbt modules for independent deployment.

- `chess-model` module — `chess.model`, `chess.model.rules`
- `chess-service` module — `chess.service`, `chess.repository`
- `chess-http` module — `chess.http`
- ZLayer wiring stays identical; module boundaries enforce the existing dependency rules

---

## Phase 6 — MongoDB + PostgreSQL Persistence

**Goal:** Replace in-memory storage with durable databases.

- `MongoGameRepository` — implements `GameRepository` trait, backed by MongoDB
- `PostgresGameRepository` — implements `GameRepository` trait, backed by PostgreSQL
- Swap in `Main.scala` (or HTTP wiring) by changing one `ZLayer` line
- Both implementations can coexist; choose at startup via config

---

## Phase 7 — Web UI

**Goal:** Browser-based board instead of the TUI.

- New module `chess-web` or new view `chess.view.HtmlBoardView`
- `GameService` and all layers below are unchanged
- WebSocket support can be added to `chess.http` to push board updates after each move

---

## Phase 8 — Authentication & Multi-game Lobby

**Goal:** Support multiple concurrent players with accounts.

- New `chess.auth` package — JWT or session-based auth
- `GameService.newGame()` accepts player IDs
- Lobby service tracks active games per player

---

## Phase 9 — Reactive Streams

**Goal:** Stream board state updates to connected clients in real time.

- Wrap `GameService.makeMove` results in a ZIO / fs2 stream
- Clients subscribe to a game stream by `GameId`
- No changes to domain logic

---

## Phase 10 — Kafka Event Publishing

**Goal:** Publish domain events to a Kafka topic for downstream consumers (analytics, replay, spectator service).

- `GameEvent` is already returned by `makeMove` — callers decide what to do with it
- Add a Kafka producer call at the HTTP/WebSocket call site
- `GameService` itself remains unchanged
