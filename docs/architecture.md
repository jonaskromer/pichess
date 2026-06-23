# Architecture

## Overview

πChess is a chess platform written in Scala 3 using ZIO throughout. The runtime is a **microservice stack** with **gRPC** for synchronous inter-service calls and **Kafka** as the asynchronous event log. The core play path is:

```
                                    ┌──────────────────────── projections (optional) ─────────────────────────┐
                                    │                                                                          │
  browser ──HTTP/SSE──▶  gateway  ──gRPC──▶  game-service  ──Kafka(chess.game-events)──▶  repository ──▶ DB    │
     │                      │                  │ (+ engine)            │                                       │
     │                      │ HTTP             │                       ├──▶ opening-service ──▶ Neo4j          │
     └──── web UI (Scala.js)┘                  └─ vs-computer          └──▶ analytics-service ──▶ ClickHouse   │
                            │                                                                                  │
                            └──▶ lobby-service (multiplayer) ──▶ DB                                            │
```

- **gateway** holds **no** authoritative game state. Each REST endpoint forwards to a gRPC rpc on game-service (or an HTTP call to lobby-service); the SSE source re-subscribes when the active game id changes. It also serves the Laminar/Scala.js web UI from the classpath.
- **game-service** owns in-memory game state via `GameSessions` (one `SubscriptionRef[SessionState]` per game id). It embeds **`bot-engine`** for vs-computer play, and after every successful state transition it publishes a `GameDomainEvent` to Kafka.
- **repository** is the persistence write-side: a Kafka consumer applies each event by writing the latest state under `gameId` through the pluggable persistence layer. A legacy REST surface is retained for Gatling and ad-hoc curl — both write paths are idempotent.
- **lobby-service** runs the multiplayer invite/join flow and registers players back with the gateway over HTTP when a game starts.
- **opening-service** and **analytics-service** are optional read-side Kafka projections — they consume `chess.game-events` and never touch game state. Unlike the repository's idempotent upsert write path, these projections are **not** replay-safe: analytics does one unconditional `INSERT` per event, and opening-service increments edge counts against an in-memory before-FEN `Ref` (lost on restart). Both consumers reset `auto.offset.reset=earliest`, so a topic replay double-counts.

All services read config from env vars (12-factor), expose Prometheus metrics, and select their persistence backend through one shared switch (`PICHESS_BACKEND` / `PICHESS_CACHE`).

For background see:
- [ADR 010 — Kafka as event log](adr/010-kafka-as-event-log.md)
- [ADR 011 — gRPC for internal RPC](adr/011-grpc-for-internal-rpc.md)
- [ADR 012 — zio-json events, no schema registry](adr/012-zio-json-event-serialization-no-schema-registry.md)
- [ADR 013 — deletion of the `app` module and `sbt run`](adr/013-deletion-of-app-module-and-sbt-run.md)
- [ADR 014 — spectator presence in the gateway](adr/014-spectator-presence-in-gateway.md)
- [ADR 015 — tournament play as a separate `bot-tournament` module](adr/015-tournament-server-integration-module.md)
- [ADR 016 — polyglot persistence behind a DAO seam](adr/016-polyglot-persistence-dao-seam.md)
- [ADR 017 — boopickle wire codec for DTOs (supersedes the FEN note in 011)](adr/017-boopickle-wire-codec-for-dtos.md)
- [ADR 018 — CQRS read-side projections (Neo4j, ClickHouse)](adr/018-cqrs-read-side-projections.md)
- [ADR 021 — distributed tracing across HTTP/gRPC/Kafka](adr/021-distributed-tracing-across-async-boundary.md)
- [ADR 022 — Spark analytics projection (Lambda read-side + live loop-back)](adr/022-spark-analytics-projection.md)

> **Historical note (pre-Phase 5/11):** πChess used to run as a single `app` Main bundling TUI + gateway + game-service in one process, with the repository reachable over a synchronous REST PUT. That monolith is **gone** — its responsibilities are split across the services above, the cross-service hop is gRPC (not in-process calls), and `sbt run` at the root is no longer wired. The package responsibilities below are preserved; only the wiring changed.

## SBT Module Map

The build is **32 sbt modules** (run `sbt projects` for the live list — it reports 34 + `root` because `domain` and `api` each expand into JVM + JS variants there). `domain` and `api` are cross-compiled to JVM **and** JS so the Scala.js web-ui shares types and DTOs with the JVM gateway. Modules group into libraries, persistence, the bot, the deployable services, and cross-cutting/test tooling:

```
root (aggregate)
│
├─ Libraries (no Docker)
│  ├── domain (JVM + JS)   chess.model — board, pieces, errors, PieceUnicode
│  ├── api (JVM + JS)      chess.api — wire DTOs + Tapir endpoint contracts
│  ├── rules               chess.model.rules — move validation, game progression, Zobrist
│  ├── codec               chess.codec + chess.notation — FEN/PGN/JSON codecs, SAN, move parsing
│  ├── events              chess.events — GameDomainEvent ADT + zio-json codecs (Kafka payloads)
│  ├── proto               generated zio-grpc stubs (state replies carry BoardStateDto bytes; FEN kept as a fallback field)
│  ├── repository-api      chess.repository.api — Tapir contract for the repo REST surface
│  ├── optimisation        chess.opt — small perf helpers shared by the postgres backend
│  └── observability       chess.obs — Metrics / Profiler / Tracing ZLayers (see performance.md)
│
├─ Persistence (DAO pattern — one module per backend)
│  ├── persistence/api         GameRepository + LobbyRepository traits, Backend enum, BackendConfig
│  ├── persistence/postgres    Slick + HikariCP
│  ├── persistence/mongo       MongoDB Scala driver
│  ├── persistence/redis       zio-redis
│  ├── persistence/cassandra   DataStax driver
│  ├── persistence/cache       CachedGameRepository — Redis decorator over any primary
│  ├── persistence/runtime     PersistenceLayers — the single switch every service Main uses
│  └── persistence/contract    shared Testcontainers contract tests (all backends behave alike)
│
├─ Bot / engine
│  ├── bot-engine          chess.bot — Search/AlphaBetaSearch, Evaluator family, EngineBundle, TT, OpeningBook
│  ├── bot-data            DuckDB-backed opening book + training-corpus accumulator
│  ├── bot-train           offline training & Elo harness (TexelTuner, SelfPlay, NnueDataGen, TournamentMain)
│  ├── bot-lichess         online Lichess Bot-API client (LichessBotMain, Bridge, GameRunner)
│  └── bot-tournament      chess.bot.tournament — NowChess tournament client (TournamentBotMain, TournamentBridge, TournamentRunner, TournamentApiClient); same engine as bot-lichess
│     (+ nnue-train/ — Python/PyTorch NNUE trainer, not an sbt module)
│
├─ Deployable services (Docker images)
│  ├── gateway            HTTP :8090 — public REST + Swagger + SSE + web-ui static; gRPC client
│  ├── game-service       gRPC :9000 — authoritative state; engine; Kafka producer
│  ├── repository         REST :8091 — persistence write-side; Kafka consumer
│  ├── lobby-service      REST :8092 — multiplayer lobby/invite flow
│  ├── opening-service    (no HTTP)  — Kafka → Neo4j opening-tree projection
│  ├── analytics-service  REST :8093 — Kafka → ClickHouse analytics projection + query API
│  └── tui                interactive terminal client against the gateway (`make tui`)
│
├─ web-ui (JS only)       chess.webui — Laminar SPA, compiled to JS, served by the gateway
│
└─ Perf / test tooling
   ├── gatling            chess.gatling — Gatling simulations + reusable Chains (see performance.md)
   └── bench              chess.bench — JMH microbenchmarks for rules/codec/domain/bot (see performance.md)
```

Each service exposes Prometheus metrics on a dedicated port (gateway 9101, game-service 9102, repository 9103, lobby 9104, opening 9105, analytics 9106).

## Module Dependency Graph

Dependencies only flow **downward**; SBT module boundaries enforce this at compile time. `domain` depends on nothing but ZIO; the contract modules (`api`, `repository-api`, `persistence/api`) carry no domain logic.

```
gateway          → game-service, codec, api, proto, observability
game-service     → domain, rules, codec, events, proto, api, persistence/{api,runtime}, observability, bot-engine
repository       → domain, repository-api, codec, events, persistence/{api,runtime}, observability
lobby-service    → domain, api, persistence/{api,runtime}, observability
opening-service  → domain, codec, events, observability
analytics-service→ domain, codec, events, observability
tui              → domain, codec, api
web-ui           → domain.js, api.js                                   (Scala.js)

bot-lichess      → domain, rules, codec, bot-engine
bot-tournament   → domain, rules, codec, bot-engine
bot-train        → domain, rules, codec, bot-data, bot-engine
bot-data         → domain, codec, bot-engine
bot-engine       → domain, rules, codec

persistence/runtime   → persistence/{api,postgres,mongo,redis,cassandra,cache}
persistence/cache     → persistence/{api,redis}
persistence/{pg,…}    → domain, codec, persistence/api   (postgres also → optimisation)
events           → domain, codec
codec            → domain, rules
rules            → domain
observability    → domain
api / repository-api / proto / persistence/api → contract-only (no domain logic)
domain           → (no internal deps)
```

No module imports from a layer above it. Adding a new persistence backend is one new module + one new case in the `PersistenceLayers` switch and the `Backend` enum — see [development.md → Adding a New Repository Implementation](development.md#adding-a-new-repository-implementation).

## Packages

### `chess.model` (module: `domain`, cross-compiled JVM + JS)

Domain types. No I/O, no dependencies on other packages.

| File | Purpose |
|---|---|
| `GameId.scala` | `type GameId = String` — single change point if stronger typing is needed later |
| `GameError.scala` | `enum GameError` representing typed failure tracks (e.g. `ParseError`, `InvalidMove`, `GameNotFound`, `InfrastructureError`) |
| `GameEvent.scala` | Domain events: `GameStarted`, `MoveMade`, `InvalidMoveAttempted` |
| `Lobby.scala` | `case class Lobby` + `enum LobbyStatus` (`Waiting` / `Full` / `Started` / `Closed`) + `enum LobbyVisibility` (`Public` / `Private`) — the multiplayer-lobby aggregate (used by `lobby-service`; surfaced through the gateway's lobby proxy) |
| `LobbyId.scala` / `InviteCode.scala` / `LobbyError.scala` | Lobby identity, shareable join code, and typed lobby failures |
| `board/Board.scala` | `type Board = Map[Position, Piece]` + initial board setup |
| `board/CastlingRights.scala` | Four booleans tracking kingside/queenside castling rights per color |
| `board/GameState.scala` | Immutable game snapshot: board, active color, en passant target, castling rights, in-check flag, status, halfmove clock, fullmove number |
| `board/GameStatus.scala` | `enum DrawReason` (`Stalemate`, `FiftyMoveRule`, `InsufficientMaterial`, `ThreefoldRepetition`, `FivefoldRepetition`) and `enum GameStatus` — `Playing`, `Checkmate(winner)`, `Draw(reason)` |
| `board/Move.scala` | A move from one `Position` to another, with optional promotion piece |
| `board/Position.scala` | A board square identified by column (`Char`) and row (`Int`) |
| `board/BoardLike.scala` / `board/PositionView.scala` | Read-only board/position seam traits — let the engine readers (move generation, NNUE/HCE eval, Zobrist, in-check) operate over either the immutable `BoardState` / `GameState` or the search-internal mutable board, so copy-make search reuses one set of readers |
| `piece/Color.scala` | `White` / `Black` with `.opposite` |
| `piece/Piece.scala` | A piece: color + type |
| `piece/PieceType.scala` | `Pawn`, `Rook`, `Knight`, `Bishop`, `Queen`, `King` |

Also in `domain`: `chess.view.PieceUnicode` — maps `(Color, PieceType)` to Unicode chess symbols; lives here so the Scala.js web-ui can use it without depending on the full view layer.

### `chess.model.rules` (module: `rules`)

Chess logic using ZIO's typed error channel. Takes `GameState` and `Move`, returns `IO[GameError, GameState]`.

| File | Purpose |
|---|---|
| `MoveValidator.scala` | Validates a move against all chess rules for all piece types, including en passant and castling. Provides `isInCheck`, `hasLegalMove`, and the legal-move enumeration used for checkmate/stalemate detection. |
| `Game.scala` | Applies a validated move to produce a new `GameState`; handles en passant, pawn promotion, castling, halfmove/fullmove counters, and detects checkmate, stalemate, and insufficient material. |
| `Ray.scala` | Reusable piece-movement primitives: per-piece-type ray tables (sliding vs single-hop) plus `walk` and `canReach` helpers used by `MoveValidator`. |
| `Zobrist.scala` | Zobrist hashing for game positions — fast position comparison for repetition detection. |

### `chess.notation` (module: `codec`)

Notation parsing and serialization. Each notation style has its own resolver implementing the `NotationResolver` trait (Strategy pattern). The resolvers are chained by `MoveParser` (Chain of Responsibility).

| File | Purpose |
|---|---|
| `NotationResolver.scala` | Trait: `parse(input, state): IO[GameError, Option[Move]]` — `None` if the notation doesn't match, `Some(move)` on success, or fails with `GameError` if recognized but invalid |
| `CoordinateResolver.scala` | Parses coordinate notation: `e2 e4`, `e2e4`, `e2-e4`, `e7e8=Q` |
| `SanResolver.scala` | Parses SAN: piece moves (`Nf3`), pawn pushes (`e4`), captures (`exd5`), promotion (`e8=Q`), disambiguation (`Nbd2`) |
| `CastlingResolver.scala` | Parses castling (`O-O`, `O-O-O`) into a king-move whose two-square step `MoveValidator` recognises as a castling attempt |
| `MoveParser.scala` | Orchestrator: chains `CoordinateResolver`, `CastlingResolver`, `SanResolver`. Lives here (not in `controller`) so `codec.PgnParser` can reuse it without inverting layering |
| `SanSerializer.scala` | `toSan(move, state)` → SAN with disambiguation/capture/promotion. Also `deriveMoveLog(initialState, moves)` to replay and serialize a whole history |

### `chess.codec` (module: `codec`)

Game-state encoding/decoding in FEN, PGN, and JSON, plus UCI move translation. FEN is the import/export format and the persistence wire format. **Three** FEN parser implementations sit side-by-side (one per parsing technique) and share semantic validation via `FenBuilder` — see [ADR 009](adr/009-recompute-derived-state-on-import.md).

| File | Purpose |
|---|---|
| `FenParser.scala` | Trait: `parse(input): IO[GameError, GameState]` — common interface for all three implementations |
| `FenParserCombinator.scala` | Built on `scala-parser-combinators` (`RegexParsers`) |
| `FenParserFastParse.scala` | Built on the `fastparse` library |
| `FenParserRegex.scala` | Built on `scala.util.matching.Regex`, no external parser library |
| `FenBuilder.scala` | Shared converter from six tokenized FEN fields to a validated `GameState`; computes `inCheck` via `MoveValidator.isInCheck` |
| `FenSerializer.scala` | `serialize(state)` → canonical FEN. Also exposes `positionKey` (first four FEN fields) reused for repetition detection |
| `FenCodec.scala` | Co-located per-field FEN encode/decode codec; both `FenBuilder` (decode) and `FenSerializer` (encode) delegate here |
| `PgnParser.scala` / `PgnSerializer.scala` / `PgnCodec.scala` | PGN move-text import/export + facade |
| `JsonParser.scala` / `JsonSerializer.scala` / `JsonCodec.scala` | JSON game-state import/export + facade (web-UI communication) |
| `UciCodec.scala` | UCI ↔ `Move` translation (`e2e4`, `e7e8q`) — the one codec still returning `Either[String, Move]` (malformed UCI is an upstream contract violation, not a runtime `GameError`). Used by `bot-lichess`, `bot-tournament`, and `DuckDbOpeningBook` |

### `chess.events` (module: `events`)

The Kafka event ADT. `chess.events.GameDomainEvent` = `GameStarted | GameLoaded | MoveMade | Undone | Redone | DrawClaimed | Forfeited | GameEnded`. Every event carries a `resultingFen` — "what to persist after this event" — so consumers stay type-agnostic. zio-json over the wire (no schema registry, [ADR 012](adr/012-zio-json-event-serialization-no-schema-registry.md)).

### `chess.api` (module: `api`, cross-compiled JVM + JS)

Wire DTOs and Tapir endpoint contracts shared between the gateway (JVM encoder) and the Scala.js web-ui (JS decoder) — single source of truth for the HTTP contract.

| File | Purpose |
|---|---|
| `BoardStateDto.scala` | Wire DTOs: `BoardStateDto`, `MoveRequest`, `LoadRequest`, `ExportResponse`, … Auto-derived zio-json codecs |
| `Endpoints.scala` | Typed Tapir endpoint descriptions for the gateway REST API |

### `chess.controller` (split across modules)

Input handling and shared move-processing logic.

| File | Module | Purpose |
|---|---|---|
| `GameController.scala` | `game-service` | Shared move-processing: `makeMove`, `undo`, `redo`, `claimDraw`. Owns history/redo stack updates on the `SubscriptionRef[SessionState]`, persists each state, and runs repetition detection (auto-promotes a fivefold repetition to a draw) |
| `WebController.scala` | `gateway` | Tapir REST + raw zio-http for the gateway: per-game `/api/games/{id}/…` commands & queries, SSE at `/api/games/{id}/events`, web-ui (`GET /`, `/web/**`) + Swagger (`/docs`), the internal lobby hand-off, plus the `LobbyProxy` and `LichessSpectate` routes. Each command is a thin shim over `GameService` (gRPC); errors map to a JSON `ErrorDto`. Full surface in the **Gateway HTTP / SSE surface** section below |
| `TuiController.scala` | `tui` | Terminal command parser + dispatcher: solo/game verbs (`quit`, `help`, `flip`, `undo`, `redo`, `draw`, `forfeit`, `new`, `load …`, `export …`), lobby/multiplayer verbs (`host [public\|private]`, `join …`, `lobbies`, `lobby`, `start`, `local`), annotation helpers (`preview …`, `threats`), and free-form moves — talking to the gateway |

### Gateway HTTP / SSE surface (`gateway`)

`WebController.routes` assembles four route groups over the game-service gRPC client (the gateway keeps **no** game state; `GameError` / gRPC status → JSON `ErrorDto`).

**Tapir REST** (interpreted from `chess.api.Endpoints`; Swagger UI at `/docs`, title *pichess API* v0.1.0):

| Method · path | Purpose |
|---|---|
| `POST /api/games` | Create a game — empty body, or `load` (FEN / PGN / JSON), or `vsBot` settings → `GameSnapshot` |
| `GET /api/games/{id}/state?format=` | Board view (default) or `fen` / `pgn` / `json` export |
| `POST /api/games/{id}/{move,undo,redo,draw,forfeit}` | Mutations; each requires the `X-Session-Id` header and is gated to a registered player |
| `GET /api/games/{id}/export/{format}` | Canonical export (alias of `state?format=`) |
| `GET /api/games/{id}/legal-moves?from=` · `/threats` · `/attackers?of=` | Server-computed annotation queries (gateway-cached) |
| `GET /api/stack-info` | Active persistence backend + projection extras |
| `POST /internal/games/{id}/players` | **Internal** lobby→gateway hand-off, excluded from Swagger. `RegisterPlayersRequest` carries `hostSessionId`, optional `guestSessionId`, and the lobby's `allowSpectate` + `spectatorLimit` policy |

**Raw zio-http**: `GET /` (HTML shell) and `GET /web/**` (web-ui assets); `GET /api/games/{id}/events` (SSE; `?role=spectator` marks the connection as a watcher); `GET /dev/coverage/report/**` and `/dev/performance/report/**` (only when `PICHESS_DEV` is set). **`LobbyProxy`** reverse-proxies `/lobbies` + `/lobbies/**` (GET/POST/PUT/DELETE/PATCH) to lobby-service so the web-ui stays same-origin. **`LichessSpectate`** adds `POST /lichess/games` only when `LICHESS_BOT_TOKEN` is configured — it challenges a random online Lichess bot, mirrors the game into game-service, and replays the public move stream so the browser watches it through the normal SSE feed.

**SSE events** on `/api/games/{id}/events`:
- `state` — board JSON (`BoardStateDto`), on every state change.
- `spectators` — live spectator count, pushed to **every** viewer (players included).
- `spectator-denied` — sent once, with no board, when a `?role=spectator` connection is refused; data is `not-allowed` or `full`.

**Spectator presence + public-lobby browser.** `SpectatorPresence` is a gateway-only transport concern (game-service stays oblivious): each game id gets a `SubscriptionRef[Int]`, a `?role=spectator` SSE stream occupies a slot for its lifetime under the game's `SpectatorPolicy` (`allowSpectate` + `spectatorLimit`, recorded by the lobby hand-off), and every viewer subscribes to the count — extending the SubscriptionRef-over-SSE pattern of [ADR 006](adr/006-subscriptionref-sse-for-ui-sync.md) to a second stream. On the lobby side, `LobbyVisibility` (`Public` / `Private`) and `LobbyStatus` (`Waiting` / `Full` / `Started` / `Closed`) live in `chess.model.Lobby`; `LobbyRepository.listPublicActive()` returns every non-`Closed` `Public` lobby (so running games are watchable, not just open seats), served at `GET /lobbies/public` and rendered by the web-ui's "Public lobbies" list and its read-only **Watch** screen (`#watch/{id}`).

### `chess.service` (module: `game-service`)

Orchestration plus the per-game session model and the gRPC entry point. These live in three sibling packages inside the `game-service` module: the orchestration types are `chess.service`, the session model `SessionState` / `GameSnapshot` is in the module-local `chess.model` package, and `GrpcServer` is in `chess.gameservice`.

| File | Package | Purpose |
|---|---|---|
| `GameService.scala` | `chess.service` | Trait: `newGame()`, `loadGame(input)` (auto-detects JSON / PGN / FEN), `makeMove(id, input)`, `getState(id)`, `saveState(id, state)`. Returns `IO[GameError, A]` |
| `GameServiceLive.scala` | `chess.service` | Live impl. `makeMove` parses → validates → applies → persists, returning `(newState, GameEvent.MoveMade)`; the producer publishes the event to Kafka |
| `SessionState.scala` | `chess.model` | `GameSnapshot` (game id, initial state, history newest-first, redo stack) + `SessionState` (snapshot + optional error/output), held in a `SubscriptionRef` |
| `GrpcServer.scala` | `chess.gameservice` | zio-grpc service impl — the `MakeMove` / `NewGame` / `GetState` RPCs the gateway calls; also drives the synchronous vs-bot reply (below) |

The **vs-computer** path lives here too: `game-service` depends on `bot-engine`, and the bot's reply is computed **synchronously inside the `MakeMove` RPC** — `GrpcServer.makeMove` applies the player's move, then `maybeBotReply` blocks on the engine's fixed-depth search and applies the bot's move through the same `GameController` path. So one vs-bot move publishes **two** `MoveMade` Kafka events (player, then bot), while the SSE subscriber sees a single merged post-bot `state` frame rather than a transient "player moved, waiting" frame.

### `chess.repository` (module: `repository`) & `chess.repository.api`

The repository microservice. `RepositoryMain` runs a Kafka consumer (group `pichess-repository`, `auto.offset.reset=earliest` — a fresh group replays the topic from offset 0) that applies each `GameDomainEvent` idempotently through the persistence layer. It also exposes a Tapir REST surface (`RepositoryServer`, from the `repository-api` contract `RepositoryEndpoints`): `PUT /games/{id}` (save), `GET /games/{id}` (load → a JSON `GameStateEnvelope{"fen":…}`, 404 if absent), `DELETE /games/{id}` (idempotent). Nothing reads through this surface at runtime — `game-service` reads and writes directly via `persistenceRuntime` (it has no dependency on `repository-api`), so the REST path is legacy, kept for Gatling and ad-hoc curl (matching the Overview). The only live HTTP caller is the Docker healthcheck, which probes `GET /games/healthcheck` and treats the resulting 404 as "up".

### Persistence (`chess.persistence.*`, modules under `persistence/`)

DAO pattern. `persistence/api` defines the `GameRepository` and `LobbyRepository` traits (the latter's `listPublicActive()` — every non-`Closed` `Public` lobby — powers the public-lobby browser), the `Backend` enum, and `BackendConfig` (reads `PICHESS_BACKEND` / `PICHESS_CACHE`). Each backend (`postgres` via Slick, `mongo`, `redis`, `cassandra`) implements both traits as `ZLayer`s; `persistence/cache` wraps any primary in a Redis-backed `CachedGameRepository`. `persistence/runtime`'s **`PersistenceLayers`** is the single switch all three stateful services (`game-service`, `repository`, `lobby-service`) consume — so a backend swap is one env var, zero code change. `persistence/contract` runs the same Testcontainers behaviour suite against every backend so they stay observationally equal.

### `chess.bot` (module: `bot-engine`) and the bot pipeline

`bot-engine` is the pure engine library: `Search` / `AlphaBetaSearch` (negamax α-β + transposition table, quiescence, null-move, SEE ordering, singular + check extensions, and a time-budgeted iterative-deepening mode), the `Evaluator` family (`Hce`, `Nnue`, `NnueEns`, `Hybrid`), `EngineBundle` (loads weights + opening book + NNUE from the classpath), and `TbAugmentedSearch` — a generic root-probe wrapper that defers low-piece endgames to an optional tablebase oracle. There is **no** bundled local Syzygy database: `EngineBundle` defaults `tablebaseOracle = None` (off), and the only concrete oracle is the **online** Lichess 7-piece tablebase HTTP API (`LichessTablebaseSearch`, in `bot-lichess`). `game-service` links it for vs-computer; `bot-lichess` plays online on Lichess and `bot-tournament` plays in external NowChess tournaments. `bot-data` (DuckDB opening book + corpus), `bot-train` (Texel tuning, self-play, NNUE data generation, the UCI_Elo-anchored `TournamentMain`), and the Python `nnue-train/` trainer make up the offline pipeline. **See [bot.md](bot.md) for the full engine + Elo-measurement reference.**

### `chess.obs` (module: `observability`)

Cross-cutting ZLayers reused by every service: a Prometheus `MetricsLayer` + scrape server, an optional zio-profiling `ProfilerLayer`, and an OpenTelemetry/Jaeger `TracingLayer` + middleware. See [performance.md](performance.md).

### `chess.view` (split across modules)

Pure rendering, no I/O. `PieceUnicode` (`domain`), `BoardView` / `MoveLogView` / `HelpView` (`tui`), `HtmlPage` / `WebBoardView` (`gateway`).

### `chess.webui` (module: `web-ui`, Scala.js only)

Laminar single-page app compiled to JavaScript and served by the gateway via classpath resources. `Main.scala` renders the board, move log, promotion dialog, and controls; `Logic.scala` holds pure board-logic helpers extracted for unit testing. See [design.md](design.md) for the UI design system.

## Key Design Decisions

See [`docs/adr/`](adr/) for the full decision records:

- [ADR 001 — `GameEvent` as a return value, not a side-effect bus](adr/001-game-event-as-return-value.md)
- [ADR 002 — `GameController` owns shared move-processing logic](adr/002-game-controller-not-used-at-runtime.md)
- [ADR 003 — ZLayer for dependency injection](adr/003-zlayer-for-dependency-injection.md)
- [ADR 004 — Notation parsing via Strategy / Chain of Responsibility](adr/004-notation-resolver-pattern.md)
- [ADR 005 — ZIO effects throughout, including domain logic](adr/005-pure-domain-model-zio-at-boundaries.md)
- [ADR 006 — SubscriptionRef + SSE for UI synchronization](adr/006-subscriptionref-sse-for-ui-sync.md)
- [ADR 007 — Promise for coordinated shutdown](adr/007-promise-for-coordinated-shutdown.md)
- [ADR 008 — Undo/redo via state history](adr/008-undo-redo-via-replay.md)
- [ADR 009 — Recompute derived state on import (`inCheck`, PGN replay)](adr/009-recompute-derived-state-on-import.md)
- [ADR 010 — Kafka as event log](adr/010-kafka-as-event-log.md)
- [ADR 011 — gRPC for internal RPC](adr/011-grpc-for-internal-rpc.md)
- [ADR 012 — zio-json events, no schema registry](adr/012-zio-json-event-serialization-no-schema-registry.md)
- [ADR 013 — deletion of the `app` module and `sbt run`](adr/013-deletion-of-app-module-and-sbt-run.md)
- [ADR 014 — spectator presence in the gateway](adr/014-spectator-presence-in-gateway.md)
- [ADR 015 — tournament play as a separate `bot-tournament` module](adr/015-tournament-server-integration-module.md)
- [ADR 016 — polyglot persistence behind a DAO seam, env-selected, contract-tested](adr/016-polyglot-persistence-dao-seam.md)
- [ADR 017 — boopickle as the binary wire codec for DTOs (supersedes ADR 011's FEN note)](adr/017-boopickle-wire-codec-for-dtos.md)
- [ADR 018 — CQRS read-side: Kafka projections into Neo4j + ClickHouse](adr/018-cqrs-read-side-projections.md)
- [ADR 019 — copy-make search via a read-only BoardLike/PositionView seam](adr/019-copy-make-search-boardlike-seam.md)
- [ADR 020 — engine as an embedded library (no engine service)](adr/020-engine-as-embedded-library.md)
- [ADR 021 — distributed tracing across HTTP/gRPC/Kafka via uniform decorators](adr/021-distributed-tracing-across-async-boundary.md)
- [ADR 022 — Spark analytics projection: Lambda read-side, stateful streaming, live loop-back, ClickHouse dropped](adr/022-spark-analytics-projection.md)

## What's Built vs. Next

The 14-phase HTWG lecture plan ([roadmap.md](roadmap.md)) is essentially complete: TUI, functional rules, three FEN parsers, REST, microservices + Docker, Slick/Postgres **and** Mongo/Redis/Cassandra persistence, the web UI, the Gatling performance work, the AI bot, ZIO-Streams reactivity, and the Kafka event log are all in. The Phase 13 "Spark" brief was satisfied with the Kafka → ClickHouse analytics projection instead of Spark.

| Area | Status | Notes |
|---|---|---|
| Persistence | **Done** | DAO trait + Postgres/Mongo/Redis/Cassandra/in-memory + Redis cache, switchable via `PICHESS_BACKEND` |
| AI bot | **Done, ongoing** | Hybrid HCE+NNUE ≈2350 Elo; live on Lichess; training pipeline in `bot-train`/`nnue-train` |
| Multiplayer | **Done** | `lobby-service` invite/join flow |
| Observability | **Done** | Prometheus + Grafana + Jaeger on every service |
| Read-side projections | **Done** | opening-tree → Neo4j, analytics → ClickHouse |
| game-service restart resilience | **Next** | replay `chess.game-events` on startup to rebuild in-memory state. `BotConfigRepository` is also in-memory only (`Ref[Map]`), so after a game-service restart vs-bot games silently revert to PvP — the bot stops auto-replying |
| k3s/k3d deployment | **Done** | Kustomize tiers (`mvp`⊂`lobbies`⊂`full`) + local Ansible pipeline (k3s or k3d) + prod-compose fallback; rollout stays local (VPN-gated box), see [deployment-plan.md](deployment-plan.md) |

## Build & Tooling

| Tool | Version | Purpose |
|---|---|---|
| sbt | 1.12.6 | Build tool (32-module multi-project) |
| Scala | 3.8.2 | Language |
| ZIO | 2.1.26 | Effect system, DI, concurrency |
| zio-http | 3.11.2 | HTTP server, SSE |
| zio-json | 0.9.0 | Auto-derived JSON codecs (`chess.codec.JsonCodec`, wire DTOs, Kafka payloads) |
| zio-kafka | 2.10.0 | Kafka producer/consumer (`events`, repository, projections) |
| zio-grpc (scalapb) | 0.6.3 | Generated gRPC stubs in `proto`; gateway↔game-service |
| Tapir | 1.11.36 | Typed HTTP endpoints, Swagger UI, sttp client for inter-service calls |
| Laminar | 17.2.0 | Scala.js reactive UI framework (`web-ui`) |
| Slick + HikariCP | 3.6.1 | Postgres backend (FRM + connection pool) |
| MongoDB Scala driver | 5.5.1 | Mongo backend |
| zio-redis | 1.1.3 | Redis backend + cache decorator |
| DataStax driver | 4.17.0 | Cassandra backend |
| clickhouse-jdbc | 0.9.0 | analytics-service projection store |
| Neo4j driver | (Bolt) | opening-service projection store |
| scala-parser-combinators / fastparse | 2.4.0 / 3.1.1 | The two library-based FEN parsers |
| Gatling | 3.13.5 | Load testing (`gatling` module, `make perf`) |
| zio-metrics-connectors / zio-opentelemetry / zio-profiling | 2.5.6 / 3.0.0-RC24 / 0.3.3 | Observability layers |
| sbt-native-packager | — | Per-service Docker images (`<svc>/Docker/publishLocal`, layered) |
| sbt-scalajs + sbt-crossproject | — | Scala.js compilation + JVM/JS cross-compilation |
| zio-test | 2.1.26 | Test framework (`ZIOSpecDefault`) |
| sbt-scoverage | 2.2.1 | Coverage instrumentation; build fails below 100% on JVM modules |
| sbt-scalafmt / scalafix | 2.5.2 / — | Formatting + lint; run `sbt scalafmtAll` / `make scalafix-fix` after changes |
