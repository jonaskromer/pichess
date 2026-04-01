# Architecture

## Overview

πChess is a chess game written in Scala 3 using ZIO throughout. The architecture is layered to support future additions — HTTP APIs, microservices, dual-database persistence, reactive streams, and Kafka — without requiring changes to the domain model.

The application runs a TUI and a web GUI simultaneously, sharing game state via `SubscriptionRef`. Both UIs delegate move processing to `GameController`, which orchestrates `GameService`, SAN serialization, and session state updates.

## Layer Diagram

```
┌─────────────────────────────────────────────────────────┐
│                      Main.scala                         │
│   (ZLayer wiring, TUI loop, HTTP server, SSE, shutdown) │
└───────┬──────────────────────────┬──────────────────────┘
        │ uses                     │ uses
┌───────▼──────────────┐   ┌───────▼──────────────────────┐
│  chess.controller    │   │         chess.view            │
│  GameController      │   │  BoardView, MoveLogView,     │
│  WebController       │   │  HelpView, HtmlPage,         │
│  MoveParser          │   │  WebBoardView, PieceUnicode   │
└───────┬──────────────┘   └───────┬──────────────────────┘
        │ uses                     │ uses
┌───────▼──────────────┐           │
│  chess.service       │           │
│  GameService trait   │           │
│  GameServiceLive     │           │
└───┬──────────┬───────┘           │
    │ uses     │ uses              │
    │  ┌───────▼──────────────┐    │
    │  │  chess.notation      │    │
    │  │  NotationResolver    │    │
    │  │  CoordinateResolver  │    │
    │  │  SanResolver         │    │
    │  │  CastlingResolver    │    │
    │  │  SanSerializer       │    │
    │  └───────┬──────────────┘    │
    │ uses     │ uses              │
┌───▼──────────▼───────────────────▼──────────────────────┐
│                    chess.model                           │
│  Board, GameState, Move, Position, Piece,               │
│  Color, PieceType, GameId, GameEvent, GameError,        │
│  SessionState                                           │
└───────┬─────────────────────────────────────────────────┘
        │ uses
┌───────▼──────────────┐   ┌──────────────────────────────┐
│  chess.model.rules   │   │     chess.repository         │
│  Game, MoveValidator │   │     GameRepository trait     │
│                      │   │     InMemoryGameRepository   │
└──────────────────────┘   └──────────────────────────────┘
```

## Packages

### `chess.model`

Domain types. No I/O, no dependencies on other packages.

| File | Purpose |
|---|---|
| `GameId.scala` | `type GameId = String` — single change point if stronger typing is needed later |
| `GameError.scala` | Defines `enum GameError` representing typed failure tracks (e.g. `ParseError`, `InvalidMove`, `GameNotFound`) |
| `GameEvent.scala` | Domain events: `GameStarted`, `MoveMade`, `InvalidMoveAttempted` |
| `SessionState.scala` | Shared mutable state: `gameId`, `GameState`, `moveLog`, `error` — held in a `SubscriptionRef` |
| `board/Board.scala` | `type Board = Map[Position, Piece]` + initial board setup |
| `board/CastlingRights.scala` | Case class with four booleans tracking kingside/queenside castling rights for each color |
| `board/GameState.scala` | Immutable game snapshot: board, active color, en passant target, castling rights, in-check flag, game status |
| `board/GameStatus.scala` | `enum GameStatus` — `Playing` or `Checkmate(winner: Color)` |
| `board/Move.scala` | A move from one `Position` to another, with optional promotion piece |
| `board/Position.scala` | A board square identified by column (`Char`) and row (`Int`) |
| `piece/Color.scala` | `White` / `Black` with `.opposite` |
| `piece/Piece.scala` | A piece: color + type |
| `piece/PieceType.scala` | `Pawn`, `Rook`, `Knight`, `Bishop`, `Queen`, `King` |

### `chess.model.rules`

Chess logic using ZIO's typed error channel. Takes `GameState` and `Move`, returns `IO[GameError, GameState]`.

| File | Purpose |
|---|---|
| `MoveValidator.scala` | Validates a move against all chess rules for all piece types, including en passant |
| `Game.scala` | Applies a validated move to produce a new `GameState`; handles en passant capture/target tracking and pawn promotion |

### `chess.notation`

Notation parsing and serialization. Each notation style has its own resolver implementing the `NotationResolver` trait (Strategy pattern). The resolvers are chained by `MoveParser` (Chain of Responsibility).

| File | Purpose |
|---|---|
| `NotationResolver.scala` | Trait: `parse(input, state): IO[GameError, Option[Move]]` — returns `None` if the notation doesn't match, `Some(move)` on success, or fails with `GameError` if recognized but invalid |
| `CoordinateResolver.scala` | Parses coordinate notation: `e2 e4`, `e2e4`, `e2-e4`, `e7e8=Q` |
| `SanResolver.scala` | Parses SAN: piece moves (`Nf3`), pawn pushes (`e4`), pawn captures (`exd5`), promotion (`e8=Q`), disambiguation (`Nbd2`) |
| `CastlingResolver.scala` | Parses castling notation (`O-O`, `O-O-O`); currently returns an error (not yet implemented) |
| `SanSerializer.scala` | `toSan(move, state): IO[GameError, String]` — serializes a `Move` + pre-move `GameState` into SAN (with disambiguation, capture notation, and promotion) |

### `chess.controller`

Input handling and shared move-processing logic.

| File | Purpose |
|---|---|
| `GameController.scala` | `makeMove(gs, session, rawInput): IO[GameError, Unit]` — shared move-processing logic used by both TUI and web. Orchestrates `GameService.makeMove`, SAN serialization, and session state update. |
| `TuiController.scala` | TUI command parsing (`quit`, `help`, `flip`, move) and dispatch. Returns `Result.Shutdown` or `Result.Continue(flipped)`. |
| `MoveParser.scala` | Orchestrator: chains `CoordinateResolver`, `CastlingResolver`, `SanResolver` in order; `parse(input, state): IO[GameError, Move]` |
| `WebController.scala` | HTTP route handlers (zio-http), SSE endpoint for state streaming, session management |

### `chess.repository`

Persistence abstraction. Designed for future swap between MongoDB and PostgreSQL.

| File | Purpose |
|---|---|
| `GameRepository.scala` | Trait: `save`, `load`, `delete` by `GameId`. Returns `IO[GameError, A]`. Companion provides ZIO accessor methods. |
| `InMemoryGameRepository.scala` | `Ref[Map[GameId, GameState]]`-backed implementation. Provided as a `ULayer[GameRepository]`. |

**Future:** `MongoGameRepository` and `PostgresGameRepository` will implement the same trait and be swapped in via ZLayer at the `Main` wiring boundary.

### `chess.service`

Orchestration layer. Coordinates domain logic, parsing, and persistence. This is the primary integration seam for future HTTP routes, WebSocket handlers, and Kafka producers.

| File | Purpose |
|---|---|
| `GameService.scala` | Trait: `newGame()`, `makeMove(id, input)`, `getState(id)`. Returns `IO[GameError, A]`. Companion provides ZIO accessors and a `layer` alias. |
| `GameServiceLive.scala` | Live implementation injected via `ZLayer.fromFunction`. Emits `GameEvent` alongside state on each move. |

**Future:** HTTP routes will call `GameService` directly. Kafka publishing will be added at the call site (`makeMove` returns the event — callers decide what to do with it).

### `chess.view`

Pure rendering. No I/O.

| File | Purpose |
|---|---|
| `BoardView.scala` | `render(state, flipped): String` — ANSI-colored board with Unicode chess symbols; supports flipped perspective |
| `MoveLogView.scala` | `render(log): String` — displays the last two moves in SAN with color-coded player labels |
| `HelpView.scala` | `render: String` — in-game help screen listing commands, notation, and implemented rules |
| `HtmlPage.scala` | `render: String` — HTML page with embedded CSS and JavaScript for the web GUI |
| `WebBoardView.scala` | `toJson(state, moveLog, error): String` — serializes game state to JSON for the web frontend |
| `PieceUnicode.scala` | Maps `(Color, PieceType)` to Unicode chess symbols |

### `chess` (root)

| File | Purpose |
|---|---|
| `Main.scala` | ZIO app entry point. Wires layers, runs TUI loop + HTTP server in parallel with `SubscriptionRef` shared state, SSE, and coordinated shutdown via `Promise`. Excluded from test coverage. |

## Dependency Rules

Dependencies only flow **downward**:

```
Main → controller → service → notation → model
                  → model.rules → model
                  → repository
     → view → model
```

No package imports from a layer above it. `chess.model` has no dependencies on any other package in this project (except ZIO itself for `IO` in `rules`).

## Key Design Decisions

See [`docs/adr/`](adr/) for the full decision records:

- [ADR 001 — `GameEvent` as a return value, not a side-effect bus](adr/001-game-event-as-return-value.md)
- [ADR 002 — `GameController` owns shared move-processing logic](adr/002-game-controller-not-used-at-runtime.md)
- [ADR 003 — ZLayer for dependency injection](adr/003-zlayer-for-dependency-injection.md)
- [ADR 004 — Notation parsing via Strategy / Chain of Responsibility](adr/004-notation-resolver-pattern.md)
- [ADR 005 — ZIO effects throughout, including domain logic](adr/005-pure-domain-model-zio-at-boundaries.md)
- [ADR 006 — SubscriptionRef + SSE for TUI/GUI synchronization](adr/006-subscriptionref-sse-for-ui-sync.md)
- [ADR 007 — Promise for coordinated shutdown](adr/007-promise-for-coordinated-shutdown.md)

## Future Integration Points

See [`docs/roadmap.md`](roadmap.md) for the full phased plan.

| Phase | Technology | Integration seam |
|-------|-----------|-----------------|
| 3 — Parser / FEN / PGN | `scala-parser-combinators` | New `chess.codec` package; no domain changes |
| 4 — HTTP / REST | **Akka HTTP** or zio-http (Routing DSL) | `GameService` trait; HTTP routes call it directly |
| 5 — Microservices | SBT multi-project + **Docker Compose** | Module boundaries mirror existing package dependencies; REST IPC between containers |
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
| sbt 1.12.6 | Build tool |
| Scala 3.8.2 | Language |
| ZIO 2.1.24 | Effect system, dependency injection, concurrency |
| zio-http 3.10.1 | HTTP server, SSE |
| zio-test 2.1.24 | Test framework |
| sbt-scoverage 2.2.1 | Coverage instrumentation; build fails below 100% |
| sbt-scalafmt 2.5.2 | Code formatting; run `sbt scalafmtAll` after any change |
