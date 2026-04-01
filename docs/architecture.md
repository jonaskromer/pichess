# Architecture

## Overview

πChess is a console chess game written in pure Scala 3 using a functional style. The architecture is deliberately layered to support future additions — HTTP access, microservices, dual-database persistence, reactive streams, and Kafka — without requiring changes to the domain model.

## Layer Diagram

```
┌─────────────────────────────────────────────────────┐
│                     Main.scala                      │
│          (ZLayer wiring + TUI game loop)            │
└──────────┬─────────────────────────────┬────────────┘
           │ uses                        │ uses
┌──────────▼──────────────────┐  ┌───────▼────────────┐
│  chess.service.GameService  │  │    chess.view      │
│  (orchestration: moves,     │  │    BoardView       │
│   persistence, events)      │  │    MoveLogView     │
└──────┬──────────┬───────────┘  │    HelpView        │
       │ uses     │ uses         └───────┬────────────┘
       │   ┌──────▼──────────────┐       │ uses
       │   │  chess.controller   │       │
       │   │  MoveParser         │       │
       │   └──────┬──────────────┘       │
       │ uses     │ uses                 │
       │   ┌──────▼──────────────┐       │
       │   │  chess.notation     │       │
       │   │  CoordinateResolver │       │
       │   │  SanResolver        │       │
       │   │  CastlingResolver   │       │
       │   │  SanSerializer      │       │
       │   └──────┬──────────────┘       │
       │ uses     │ uses                 │
┌──────▼──────────▼───────────────────────────────────┐
│                   chess.model                       │
│  Board, GameState, Move, Position, Piece,           │
│  Color, PieceType, GameId, GameEvent                │
└─────────────────────────────────────────────────────┘
       │ uses
┌──────▼──────────────────┐  ┌─────────────────────────┐
│   chess.model.rules     │  │    chess.repository     │
│   Game, MoveValidator   │  │    GameRepository trait │
│   (pure domain logic)   │  │    InMemoryGameRepo     │
└─────────────────────────┘  └─────────────────────────┘
```

## Packages

### `chess.model`

Pure domain types. No I/O, no ZIO, no dependencies on other packages.

| File | Purpose |
|---|---|
| `GameId.scala` | `type GameId = String` — single change point if stronger typing is needed later |
| `GameError.scala` | Defines `enum GameError` representing typed failure tracks (e.g. `ParseError`, `InvalidMove`) |
| `GameEvent.scala` | Domain events: `GameStarted`, `MoveMade`, `InvalidMoveAttempted` |
| `board/Board.scala` | `type Board = Map[Position, Piece]` + initial board setup |
| `board/GameState.scala` | Immutable game snapshot: board, active color, en passant target |
| `board/Move.scala` | A move from one `Position` to another, with optional promotion piece |
| `board/Position.scala` | A board square identified by column (`Char`) and row (`Int`) |
| `piece/Color.scala` | `White` / `Black` with `.opposite` |
| `piece/Piece.scala` | A piece: color + type |
| `piece/PieceType.scala` | `Pawn`, `Rook`, `Knight`, `Bishop`, `Queen`, `King` |

### `chess.model.rules`

Pure chess logic. Takes `GameState` and `Move`, returns `Either[GameError, GameState]`. No side effects.

| File | Purpose |
|---|---|
| `MoveValidator.scala` | Validates a move against all chess rules for all piece types, including en passant |
| `Game.scala` | Applies a validated move to produce a new `GameState`; handles en passant capture/target tracking and pawn promotion |

### `chess.notation`

Notation parsing and serialization. Each notation style has its own resolver implementing the `NotationResolver` trait (Strategy pattern). The resolvers are chained by `MoveParser` (Chain of Responsibility).

| File | Purpose |
|---|---|
| `NotationResolver.scala` | Trait: `parse(input, state): Option[Either[GameError, Move]]` — returns `None` if the notation doesn't match, `Some(Left/Right)` if it does |
| `CoordinateResolver.scala` | Parses coordinate notation: `e2 e4`, `e2e4`, `e2-e4`, `e7e8=Q` |
| `SanResolver.scala` | Parses SAN: piece moves (`Nf3`), pawn pushes (`e4`), pawn captures (`exd5`), promotion (`e8=Q`), disambiguation (`Nbd2`) |
| `CastlingResolver.scala` | Parses castling notation (`O-O`, `O-O-O`); currently returns an error (not yet implemented) |
| `SanSerializer.scala` | `toSan(move, state): String` — serializes a `Move` + pre-move `GameState` into SAN (with disambiguation, capture notation, and promotion) |

### `chess.controller`

Pure input-handling functions. No ZIO, no state.

| File | Purpose |
|---|---|
| `MoveParser.scala` | Orchestrator: chains `CoordinateResolver`, `CastlingResolver`, `SanResolver` in order; `parse(input, state): Either[GameError, Move]` |
| `GameController.scala` | Thin combinator: routes `"quit"` → `None`, otherwise delegates to `MoveParser` + `Game`. Not used at runtime (Main handles `"quit"` directly and calls `GameService`); retained as a tested pure utility. |

### `chess.repository`

Persistence abstraction. Designed for future swap between MongoDB and PostgreSQL.

| File | Purpose |
|---|---|
| `GameRepository.scala` | Trait: `save`, `load`, `delete` by `GameId`. Companion provides ZIO accessor methods. |
| `InMemoryGameRepository.scala` | `Ref[Map[GameId, GameState]]`-backed implementation. Provided as a `ULayer[GameRepository]`. |

**Future:** `MongoGameRepository` and `PostgresGameRepository` will implement the same trait and be swapped in via ZLayer at the `Main` wiring boundary.

### `chess.service`

Orchestration layer. Coordinates domain logic, parsing, and persistence. This is the primary integration seam for future HTTP routes, WebSocket handlers, and Kafka producers.

| File | Purpose |
|---|---|
| `GameService.scala` | Trait: `newGame()`, `makeMove(id, input)`, `getState(id)`. Companion provides ZIO accessors and a `layer` alias. |
| `GameServiceLive.scala` | Live implementation injected via `ZLayer.fromFunction`. Emits `GameEvent` alongside state on each move. |

**Future:** HTTP routes will call `GameService` directly. Kafka publishing will be added at the call site (`makeMove` returns the event — callers decide what to do with it).

### `chess.view`

Pure rendering. No I/O.

| File | Purpose |
|---|---|
| `BoardView.scala` | `render(state, flipped): String` — ANSI-colored board with Unicode chess symbols; supports flipped perspective |
| `MoveLogView.scala` | `render(log): String` — displays the last two moves in SAN with color-coded player labels |
| `HelpView.scala` | `render: String` — in-game help screen listing commands, notation, and implemented rules |

**Future:** `HtmlBoardView`, `JsonBoardView` etc. will be separate modules consuming the same `GameState`.

### `chess` (root)

| File | Purpose |
|---|---|
| `Main.scala` | ZIO app entry point. Wires `GameService.layer` + `InMemoryGameRepository.layer`, then runs the TUI game loop with move log tracking. Excluded from test coverage. |

## Dependency Rules

Dependencies only flow **downward**:

```
Main → service → controller → notation → model
               → model.rules → model
               → repository
     → view → model
```

No package imports from a layer above it. `chess.model` has no dependencies on any other package in this project.

## Key Design Decisions

See [`docs/adr/`](adr/) for the full decision records:

- [ADR 001 — `GameEvent` as a return value, not a side-effect bus](adr/001-game-event-as-return-value.md)
- [ADR 002 — `GameController` exists but is not used at runtime](adr/002-game-controller-not-used-at-runtime.md)
- [ADR 003 — ZLayer for dependency injection](adr/003-zlayer-for-dependency-injection.md)
- [ADR 004 — Notation parsing via Strategy / Chain of Responsibility](adr/004-notation-resolver-pattern.md)
- [ADR 005 — Pure domain model; ZIO only at boundaries](adr/005-pure-domain-model-zio-at-boundaries.md)
- [ADR 006 — SubscriptionRef + SSE for TUI/GUI synchronization](adr/006-subscriptionref-sse-for-ui-sync.md)
- [ADR 007 — Promise for coordinated shutdown](adr/007-promise-for-coordinated-shutdown.md)

## Future Integration Points

See [`docs/roadmap.md`](roadmap.md) for the full phased plan.

| Phase | Technology | Integration seam |
|-------|-----------|-----------------|
| 3 — Parser / FEN / PGN | `scala-parser-combinators` | New `chess.codec` package; no domain changes |
| 4 — HTTP / REST | **Akka HTTP** (Routing DSL) | `GameService` trait; HTTP routes call it directly |
| 5 — Microservices | SBT multi-project + **Docker** | Module boundaries mirror existing package dependencies; REST IPC between containers |
| 6 — Persistence | MongoDB + PostgreSQL | `GameRepository` trait; new impls swap in via a single ZLayer line in `Main.scala` |
| 7 — Web UI | Browser-based view | View layer must stay separate from transport |
| 8 — Performance | Gatling (load) + JMH (micro) | REST API (Phase 4) is the Gatling target; avoid blocking in hot paths |
| 9 — Bot / AI | Pluggable move strategy | Bot calls `GameService.makeMove` |
| 10 — Reactive | fs2 / ZIO streams | `GameService.makeMove` return value is the stream publishing seam |
| 11 — Kafka | Kafka producer | `(newState, event)` return from `makeMove` is the integration point |
| 12 — Spark | Spark batch jobs | Consume Kafka events from Phase 11 |
| 13 — Tournament | Multi-game logic | Builds on Bot (Phase 9) + Kafka (Phase 11) |

## Build & Tooling

| Tool | Purpose |
|---|---|
| sbt 1.12.6 | Build tool |
| Scala 3.8.2 | Language |
| ZIO 2.1.14 | Effect system, dependency injection |
| ScalaTest 3.2.19 | Test framework (FlatSpec + Matchers) |
| sbt-scoverage 2.2.1 | Coverage instrumentation; build fails below 100% |
| sbt-scalafmt 2.5.2 | Code formatting; run `sbt scalafmtAll` after any change |
