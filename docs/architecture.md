# Architecture

## Overview

πChess is a chess game written in Scala 3 using ZIO throughout. The runtime architecture is a **four-container microservice stack** (kafka + game-service + repository + gateway), with **gRPC** between gateway and game-service, **Kafka** as the event log between game-service (producer) and repository (consumer), and a Laminar/Scala.js web UI served from the gateway.

```
   browser ──HTTP/SSE──▶  gateway  ──gRPC──▶  game-service  ──Kafka──▶  repository
                                                                ▲
                                                       chess.game-events topic
```

- **gateway** holds **no** authoritative game state — only a `SubscriptionRef[String]` tracking the current game id. Each REST endpoint forwards to a gRPC rpc; the SSE source re-subscribes when the active game id changes.
- **game-service** owns in-memory game state via `GameSessions` (one `SubscriptionRef[SessionState]` per game id). After every successful state transition it publishes a `GameDomainEvent` to Kafka.
- **repository** keeps a Kafka consumer that applies each event by writing the latest FEN under `gameId`. The legacy REST PUT surface is retained for Gatling and ad-hoc curl — both write paths are idempotent.

For background see:
- [ADR 010 — Kafka as event log](adr/010-kafka-as-event-log.md)
- [ADR 011 — gRPC for internal RPC](adr/011-grpc-for-internal-rpc.md)
- [ADR 012 — zio-json events, no schema registry](adr/012-zio-json-event-serialization-no-schema-registry.md)
- [ADR 013 — deletion of `app` module and `sbt run`](adr/013-deletion-of-app-module-and-sbt-run.md)

> **Historical note (pre-Phase 11):** The application used to run as a single `app` Main bundling TUI + gateway + game-service in one process, with the repository reachable over a synchronous REST PUT. That monolith is gone; the description below preserves the per-module responsibilities, but the wiring is now per-service and the cross-service hop is gRPC, not in-process method calls.

## SBT Module Map

The project is split into 13 SBT sub-projects (Phase 5). Cross-compiled modules (`domain`, `api`) target both JVM and JS so the Scala.js web-ui shares types and DTOs with the JVM gateway.

```
root (aggregate)
├── domain (JVM + JS)     chess.model  — board, pieces, errors, PieceUnicode
├── api (JVM + JS)        chess.api    — wire DTOs (BoardStateDto) + Tapir endpoints
├── rules                 chess.model.rules — move validation, game progression, Zobrist
├── codec                 chess.codec + chess.notation — FEN/PGN/JSON codecs, SAN, move parsing
├── repository-api        chess.repository.api — Tapir endpoint contract for repo microservice
├── repository            chess.repository — GameRepository impls + RepositoryServer
├── game-service          chess.service + chess.controller.GameController + SessionState
├── gateway               chess.controller.WebController + chess.view (HtmlPage, WebBoardView)
├── tui                   chess.controller.TuiController + chess.view (BoardView, HelpView, MoveLogView)
├── app                   chess.Main — composition root (TUI + gateway in one process)
├── observability         chess.obs — MetricsLayer, MetricsHttpServer, ProfilerLayer, TracingLayer, TracingMiddleware (see [performance.md](performance.md))
├── bench                 chess.bench — JMH microbenchmarks for rules/codec/domain (see [performance.md](performance.md))
├── gatling               chess.gatling — Gatling simulations + reusable Chains (see [performance.md](performance.md))
└── web-ui (JS only)      chess.webui — Laminar SPA (compiled to JS, served by gateway)
```

## Layer Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                   app / Main.scala                          │
│  (ZLayer wiring, TUI loop, HTTP server, SSE, shutdown)      │
└──────┬──────────────────┬────────────────┬──────────────────┘
       │ uses             │ uses           │ uses
┌──────▼─────────┐ ┌─────▼──────────┐ ┌───▼──────────────────┐
│  tui           │ │  gateway       │ │  web-ui (Scala.js)   │
│  TuiController │ │  WebController │ │  Laminar SPA         │
│  BoardView     │ │  HtmlPage      │ │  Logic, Main         │
│  MoveLogView   │ │  WebBoardView  │ │  (api DTOs)          │
│  HelpView      │ │  (Tapir+SSE)   │ └──────────────────────┘
└──────┬─────────┘ └─────┬──────────┘
       │ uses             │ uses
┌──────▼──────────────────▼───────────────┐
│  game-service                           │
│  GameController, SessionState           │
│  GameService trait, GameServiceLive      │
└──────┬──────────┬───────────────────────┘
       │ uses     │ uses
┌──────▼──────┐ ┌─▼──────────────────────────────────────────┐
│ repository  │ │  codec                                     │
│ GameRepo    │ │  chess.codec: FenParser(×3), FenSerializer,│
│ InMemory    │ │    FenCodec, JsonCodec, PgnCodec,          │
│ HttpGame    │ │    PgnParser, PgnSerializer, JsonParser,   │
│ RepoServer  │ │    JsonSerializer, FenBuilder              │
└──────┬──────┘ │  chess.notation: MoveParser, SanSerializer,│
       │        │    CoordinateResolver, SanResolver,        │
       │        │    CastlingResolver, NotationResolver       │
       │        └──┬─────────────────────────────────────────┘
       │ uses      │ uses
┌──────▼───────────▼──────────────────────────────────────────┐
│  rules                                                      │
│  Game, MoveValidator, Ray, Zobrist                          │
└──────┬──────────────────────────────────────────────────────┘
       │ uses
┌──────▼──────────────────────────────────────────────────────┐
│  domain (cross-compiled JVM + JS)                           │
│  chess.model: Board, GameState, Move, Position, Piece,      │
│    Color, PieceType, GameId, GameEvent, GameError,          │
│    CastlingRights, GameStatus, DrawReason                   │
│  chess.view: PieceUnicode                                   │
└─────────────────────────────────────────────────────────────┘

  Separate contract modules (no domain logic):
┌────────────────────┐   ┌────────────────────────────┐
│  api (JVM + JS)    │   │  repository-api             │
│  BoardStateDto     │   │  RepositoryEndpoints        │
│  Endpoints (Tapir) │   │  GameStateEnvelope          │
└────────────────────┘   └────────────────────────────┘
```

## Packages

### `chess.model` (module: `domain`, cross-compiled JVM + JS)

Domain types. No I/O, no dependencies on other packages.

| File | Purpose |
|---|---|
| `GameId.scala` | `type GameId = String` — single change point if stronger typing is needed later |
| `GameError.scala` | Defines `enum GameError` representing typed failure tracks (e.g. `ParseError`, `InvalidMove`, `GameNotFound`, `InfrastructureError`) |
| `GameEvent.scala` | Domain events: `GameStarted`, `MoveMade`, `InvalidMoveAttempted` |
| `board/Board.scala` | `type Board = Map[Position, Piece]` + initial board setup |
| `board/CastlingRights.scala` | Case class with four booleans tracking kingside/queenside castling rights for each color |
| `board/GameState.scala` | Immutable game snapshot: board, active color, en passant target, castling rights, in-check flag, game status, halfmove clock, fullmove number |
| `board/GameStatus.scala` | `enum DrawReason` (`Stalemate`, `FiftyMoveRule`, `InsufficientMaterial`, `ThreefoldRepetition`, `FivefoldRepetition`) and `enum GameStatus` — `Playing`, `Checkmate(winner: Color)`, or `Draw(reason: DrawReason)` |
| `board/Move.scala` | A move from one `Position` to another, with optional promotion piece |
| `board/Position.scala` | A board square identified by column (`Char`) and row (`Int`) |
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
| `Ray.scala` | Reusable piece-movement primitives: per-piece-type ray tables (king/queen/rook/bishop = sliding, knight = single-hop) plus `walk` and `canReach` helpers used by `MoveValidator`. |
| `Zobrist.scala` | Zobrist hashing for game positions — provides fast position comparison for repetition detection. |

### `chess.notation` (module: `codec`)

Notation parsing and serialization. Each notation style has its own resolver implementing the `NotationResolver` trait (Strategy pattern). The resolvers are chained by `MoveParser` (Chain of Responsibility). Lives in the `codec` module alongside the FEN/PGN/JSON codecs.

| File | Purpose |
|---|---|
| `NotationResolver.scala` | Trait: `parse(input, state): IO[GameError, Option[Move]]` — returns `None` if the notation doesn't match, `Some(move)` on success, or fails with `GameError` if recognized but invalid |
| `CoordinateResolver.scala` | Parses coordinate notation: `e2 e4`, `e2e4`, `e2-e4`, `e7e8=Q` |
| `SanResolver.scala` | Parses SAN: piece moves (`Nf3`), pawn pushes (`e4`), pawn captures (`exd5`), promotion (`e8=Q`), disambiguation (`Nbd2`) |
| `CastlingResolver.scala` | Parses castling notation (`O-O`, `O-O-O`) into a king-move whose two-square horizontal step `MoveValidator` recognises as a castling attempt. |
| `MoveParser.scala` | Orchestrator: chains `CoordinateResolver`, `CastlingResolver`, `SanResolver` in order; `parse(input, state): IO[GameError, Move]`. Lives here (not in `controller`) so `codec.PgnParser` can reuse it without inverting layering. |
| `SanSerializer.scala` | `toSan(move, state): IO[GameError, String]` — serializes a `Move` + pre-move `GameState` into SAN (with disambiguation, capture notation, and promotion). Also provides `deriveMoveLog(initialState, moves)` to replay and serialize an entire move history. |

### `chess.codec` (module: `codec`)

Game state encoding and decoding in multiple formats: FEN (Forsyth–Edwards Notation), PGN (Portable Game Notation), and JSON. FEN is used for game import/export and as the persistence wire format for the repository microservice's REST API. Three FEN parser implementations are provided side-by-side, each demonstrating a different parsing technique; they all share the same semantic validation through `FenBuilder`. PGN support covers move-text import/export. JSON is used for web GUI communication.

| File | Purpose |
|---|---|
| `FenParser.scala` | Trait: `parse(input: String): Either[String, GameState]`. Common interface that all three parser implementations satisfy. |
| `FenParserCombinator.scala` | Implementation built on `scala-parser-combinators` (`RegexParsers`). |
| `FenParserFastParse.scala` | Implementation built on the `fastparse` library. |
| `FenParserRegex.scala` | Implementation built on `scala.util.matching.Regex` with no external parser library. |
| `FenBuilder.scala` | Shared converter from the six tokenized FEN fields to a validated `GameState`. Computes `inCheck` via `MoveValidator.isInCheck`. |
| `FenSerializer.scala` | `serialize(state: GameState): String` — emits the canonical FEN string for a game state, including the halfmove clock and fullmove number. Also exposes `positionKey` (the first four FEN fields) which `GameController` reuses for threefold/fivefold repetition detection. |
| `FenCodec.scala` | Convenience facade combining `FenParserRegex` and `FenSerializer` for round-trip FEN encoding. |
| `PgnParser.scala` | Parses PGN (Portable Game Notation) move text into a list of moves. |
| `PgnSerializer.scala` | Serializes a game's move history into PGN format. |
| `PgnCodec.scala` | Convenience facade combining `PgnParser` and `PgnSerializer`. |
| `JsonParser.scala` | Parses a JSON representation of game state. |
| `JsonSerializer.scala` | Serializes game state to JSON. |
| `JsonCodec.scala` | Combines `JsonParser` and `JsonSerializer` for round-trip JSON encoding. |

### `chess.controller`

Input handling and shared move-processing logic. Split across three modules:

| File | Module | Purpose |
|---|---|---|
| `GameController.scala` | `game-service` | Shared move-processing logic used by both TUI and web: `makeMove`, `undo`, `redo`, `claimDraw`. Owns the history/redo stack updates on the `SubscriptionRef[SessionState]`, persists each state via `GameService.saveState`, and runs repetition detection (`positionKey`, `countCurrentPosition`, `isFivefoldRepetition`) so a fivefold repetition is auto-promoted to a draw. |
| `TuiController.scala` | `tui` | TUI command parser + dispatcher. Recognises `quit`, `help`, `flip`, `undo`, `redo`, `draw`, `load <text>`, `export fen|pgn|json`, and free-form moves; returns `Result.Shutdown` or `Result.Continue(flipped)`. |
| `WebController.scala` | `gateway` | zio-http + Tapir route handlers for `GET /`, `GET /api/state`, `GET /api/events` (SSE), `POST /api/move`, `/undo`, `/redo`, `/draw`, `/new`, `/load`, `/quit`, `GET /api/export/:format`, `GET /docs` (Swagger UI). Delegates all game logic to `GameController` and converts `GameError` into JSON error responses. |

### `chess.model.SessionState` (module: `game-service`)

| File | Purpose |
|---|---|
| `SessionState.scala` | `GameSnapshot` (game ID, initial state, history as `List[(Move, GameState)]` newest-first, redo stack) and `SessionState` (snapshot + optional error/output) — held in a `SubscriptionRef`. Current state is derived from `history.head` or `initialState`. |

### `chess.repository` (module: `repository`)

Persistence abstraction with in-memory and HTTP-backed implementations. The repository is also deployable as a standalone microservice via `RepositoryServer`.

| File | Purpose |
|---|---|
| `GameRepository.scala` | Trait: `save`, `load`, `delete` by `GameId`. Returns `IO[GameError, A]`. Companion provides ZIO accessor methods. |
| `InMemoryGameRepository.scala` | `Ref[Map[GameId, GameState]]`-backed implementation. Provided as a `ULayer[GameRepository]`. Used for local dev. |
| `HttpGameRepository.scala` | Tapir sttp client that calls the repository microservice over REST. Used when `REPOSITORY_URL` is set. Errors map to `GameError.InfrastructureError`. |
| `RepositoryServer.scala` | Tapir-backed HTTP server that exposes `GameRepository` over REST. Wire format is FEN. |
| `RepositoryMain.scala` | ZIO app entry point for the standalone repository microservice (port 8091). |

### `chess.repository.api` (module: `repository-api`)

| File | Purpose |
|---|---|
| `RepositoryEndpoints.scala` | Tapir endpoint descriptions for the repository microservice REST contract. Shared by `RepositoryServer` (server) and `HttpGameRepository` (client). Wire format for `GameState` is FEN. |

**Future:** `MongoGameRepository` and `PostgresGameRepository` will implement the same `GameRepository` trait and be swapped in via ZLayer.

### `chess.api` (module: `api`, cross-compiled JVM + JS)

Wire DTOs and Tapir endpoint contracts shared between the gateway (JVM encoder) and the Scala.js web-ui (JS decoder). Single source of truth for the HTTP contract.

| File | Purpose |
|---|---|
| `BoardStateDto.scala` | Wire DTOs: `BoardStateDto`, `MoveRequest`, `LoadRequest`, `ExportResponse`, etc. Auto-derived zio-json codecs. |
| `Endpoints.scala` | Typed Tapir endpoint descriptions for the gateway REST API (state, move, undo, redo, draw, new, load, export, quit). |

### `chess.service` (module: `game-service`)

Orchestration layer. Coordinates domain logic, parsing, and persistence. This is the primary integration seam for HTTP routes, WebSocket handlers, and future Kafka producers.

| File | Purpose |
|---|---|
| `GameService.scala` | Trait: `newGame()`, `loadGame(input)` (auto-detects JSON / PGN / FEN), `makeMove(id, input)`, `getState(id)`, `saveState(id, state)`. Returns `IO[GameError, A]`. Companion provides ZIO accessors and a `layer` alias. |
| `GameServiceLive.scala` | Live implementation injected via `ZLayer.fromFunction`. `makeMove` parses → validates → applies → persists, returning `(newState, GameEvent.MoveMade)`. `loadGame` tries `JsonParser`, then `PgnParser` (whose move history is preserved for undo/redo), then `FenParserRegex`. |

HTTP routes (`WebController` in `gateway`) call `GameService` directly. **Future:** Kafka publishing will be added at the call site (`makeMove` returns the event — callers decide what to do with it).

### `chess.view`

Pure rendering. No I/O. Split across three modules:

| File | Module | Purpose |
|---|---|---|
| `PieceUnicode.scala` | `domain` | Maps `(Color, PieceType)` to Unicode chess symbols |
| `BoardView.scala` | `tui` | `render(state, flipped): String` — ANSI-colored board with Unicode chess symbols; supports flipped perspective |
| `MoveLogView.scala` | `tui` | `render(log): String` — displays the last two moves in SAN with color-coded player labels |
| `HelpView.scala` | `tui` | `render: String` — in-game help screen listing commands, notation, and implemented rules |
| `HtmlPage.scala` | `gateway` | `render: String` — HTML page that loads the Scala.js web-ui bundle |
| `WebBoardView.scala` | `gateway` | `toJson(state, moveLog, error): String` — serializes game state to JSON for the web frontend |

### `chess.webui` (module: `web-ui`, Scala.js only)

Laminar single-page app compiled to JavaScript and served by the gateway via classpath resources.

| File | Purpose |
|---|---|
| `Main.scala` | Laminar app entry point — renders the board, move log, promotion dialog, and control buttons |
| `Logic.scala` | Pure board-logic helpers (legal-move highlighting, drag-and-drop validation) extracted for unit testing |

### `chess` (module: `app`)

| File | Purpose |
|---|---|
| `Main.scala` | ZIO app entry point. Wires layers, runs the TUI loop + HTTP server in parallel with `SubscriptionRef` shared state, SSE, and coordinated shutdown via `Promise`. Honours `--headless` to skip the GUI. Selects `HttpGameRepository` (when `REPOSITORY_URL` is set) or `InMemoryGameRepository` for persistence. |

## Dependency Rules

Dependencies only flow **downward**. SBT module boundaries enforce this at compile time:

```
app → tui, gateway, game-service, repository, codec
tui → game-service, codec
gateway → game-service, codec, api
web-ui → domain, api                                  (Scala.js)
game-service → domain, rules, codec, repository
repository → domain, repository-api, codec
codec → domain, rules
rules → domain
api → (standalone, cross-compiled)
repository-api → (standalone)
domain → (no internal deps)
```

No module imports from a layer above it. `domain` has no dependencies on any other module in this project (except ZIO itself for `IO`). The `api` and `repository-api` modules are contract-only and depend on Tapir + zio-json, not on domain logic.

## Key Design Decisions

See [`docs/adr/`](adr/) for the full decision records:

- [ADR 001 — `GameEvent` as a return value, not a side-effect bus](adr/001-game-event-as-return-value.md)
- [ADR 002 — `GameController` owns shared move-processing logic](adr/002-game-controller-not-used-at-runtime.md)
- [ADR 003 — ZLayer for dependency injection](adr/003-zlayer-for-dependency-injection.md)
- [ADR 004 — Notation parsing via Strategy / Chain of Responsibility](adr/004-notation-resolver-pattern.md)
- [ADR 005 — ZIO effects throughout, including domain logic](adr/005-pure-domain-model-zio-at-boundaries.md)
- [ADR 006 — SubscriptionRef + SSE for TUI/GUI synchronization](adr/006-subscriptionref-sse-for-ui-sync.md)
- [ADR 007 — Promise for coordinated shutdown](adr/007-promise-for-coordinated-shutdown.md)
- [ADR 008 — Undo/redo via state history](adr/008-undo-redo-via-replay.md)
- [ADR 009 — Recompute derived state on import (`inCheck`, PGN replay)](adr/009-recompute-derived-state-on-import.md)

## Future Integration Points

See [`docs/roadmap.md`](roadmap.md) for the full phased plan. Phases 1–5 and the Phase 7 web UI are complete.

| Phase | Technology | Integration seam |
|-------|-----------|-----------------|
| 6 — Persistence (Slick) | **Slick** (PostgreSQL) + DAO pattern | `GameRepository` trait; new impl swaps in via a single ZLayer line |
| 7 — Persistence (Mongo) | **MongoDB** Scala driver | Second `GameRepository` impl behind same DAO trait |
| 8 — Performance | **Gatling** load tests | REST API (Phase 4) is the Gatling target; optimize and show improvement |
| 9 — Bot / AI | Pluggable move strategy | Bot calls `GameService.makeMove` |
| 10 — Reactive | **Akka Streams** or ZIO Streams | `GameService.makeMove` return value is the stream publishing seam |
| 11 — Kafka | **Alpakka Kafka** or ZIO Kafka | `(newState, event)` return from `makeMove` is the integration point |
| 13 — Spark | **Apache Spark** + Kafka | Consume Kafka events; aggregate game data |

## Build & Tooling

| Tool | Purpose |
|---|---|
| sbt 1.12.6 | Build tool (13-module multi-project) |
| Scala 3.8.2 | Language |
| ZIO 2.1.24 | Effect system, dependency injection, concurrency |
| zio-http 3.10.1 | HTTP server, SSE |
| zio-json 0.9.0 | Auto-derived JSON codecs (used in `chess.codec.JsonCodec` and wire DTOs) |
| zio-process 0.7.2 | Spawning the system browser on startup (used in `Main.openBrowser`) |
| Tapir 1.11.36 | Typed HTTP endpoint descriptions, Swagger UI, sttp client for inter-service calls |
| Laminar 17.2.0 | Scala.js reactive UI framework (web-ui module) |
| scala-parser-combinators 2.4.0 | Parser combinators (used in `chess.codec`) |
| fastparse 3.1.1 | Macro-based parser library (used in `chess.codec`) |
| sbt-scalajs + sbt-crossproject | Scala.js compilation and JVM/JS cross-compilation |
| sbt-native-packager | Docker image packaging (`sbt Docker/publishLocal`) |
| zio-test 2.1.24 | Test framework |
| sbt-scoverage 2.2.1 | Coverage instrumentation; build fails below 100% |
| sbt-scalafmt 2.5.2 | Code formatting; run `sbt scalafmtAll` after any change |
