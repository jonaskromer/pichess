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
    │  ┌───────▼──────────────┐    │   ┌──────────────────────┐
    │  │  chess.notation      │    │   │  chess.codec         │
    │  │  NotationResolver    │    │   │  FenParser trait     │
    │  │  CoordinateResolver  │    │   │  FenParserCombinator │
    │  │  SanResolver         │    │   │  FenParserFastParse  │
    │  │  CastlingResolver    │    │   │  FenParserRegex      │
    │  │  SanSerializer       │    │   │  FenBuilder          │
    │  └───────┬──────────────┘    │   │  FenSerializer       │
    │ uses     │ uses              │   └───────┬──────────────┘
┌───▼──────────▼───────────────────▼───────────▼──────────┐
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
| `SessionState.scala` | `GameSnapshot` (game ID, initial state, history as `List[(Move, GameState)]` newest-first, redo stack) and `SessionState` (snapshot + optional error/output) — held in a `SubscriptionRef`. Current state is derived from `history.head` or `initialState`. |
| `board/Board.scala` | `type Board = Map[Position, Piece]` + initial board setup |
| `board/CastlingRights.scala` | Case class with four booleans tracking kingside/queenside castling rights for each color |
| `board/GameState.scala` | Immutable game snapshot: board, active color, en passant target, castling rights, in-check flag, game status |
| `board/GameStatus.scala` | `enum DrawReason` (`Stalemate`, `FiftyMoveRule`, `InsufficientMaterial`, `ThreefoldRepetition`, `FivefoldRepetition`) and `enum GameStatus` — `Playing`, `Checkmate(winner: Color)`, or `Draw(reason: DrawReason)` |
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
| `Game.scala` | Applies a validated move to produce a new `GameState`; handles en passant, pawn promotion, stalemate, 50-move rule, and insufficient material detection |

### `chess.notation`

Notation parsing and serialization. Each notation style has its own resolver implementing the `NotationResolver` trait (Strategy pattern). The resolvers are chained by `MoveParser` (Chain of Responsibility).

| File | Purpose |
|---|---|
| `NotationResolver.scala` | Trait: `parse(input, state): IO[GameError, Option[Move]]` — returns `None` if the notation doesn't match, `Some(move)` on success, or fails with `GameError` if recognized but invalid |
| `CoordinateResolver.scala` | Parses coordinate notation: `e2 e4`, `e2e4`, `e2-e4`, `e7e8=Q` |
| `SanResolver.scala` | Parses SAN: piece moves (`Nf3`), pawn pushes (`e4`), pawn captures (`exd5`), promotion (`e8=Q`), disambiguation (`Nbd2`) |
| `CastlingResolver.scala` | Parses castling notation (`O-O`, `O-O-O`); currently returns an error (not yet implemented) |
| `SanSerializer.scala` | `toSan(move, state): IO[GameError, String]` — serializes a `Move` + pre-move `GameState` into SAN (with disambiguation, capture notation, and promotion). Also provides `deriveMoveLog(initialState, moves)` to replay and serialize an entire move history. |

### `chess.codec`

Game state encoding and decoding in multiple formats: FEN (Forsyth–Edwards Notation), PGN (Portable Game Notation), and JSON. FEN is used for game import/export and as the persistence wire format for the REST API introduced in phase 4. Three FEN parser implementations are provided side-by-side, each demonstrating a different parsing technique; they all share the same semantic validation through `FenBuilder`. PGN support covers move-text import/export. JSON is used for web GUI communication.

| File | Purpose |
|---|---|
| `FenParser.scala` | Trait: `parse(input: String): Either[String, GameState]`. Common interface that all three parser implementations satisfy. |
| `FenParserCombinator.scala` | Implementation built on `scala-parser-combinators` (`RegexParsers`). |
| `FenParserFastParse.scala` | Implementation built on the `fastparse` library. |
| `FenParserRegex.scala` | Implementation built on `scala.util.matching.Regex` with no external parser library. |
| `FenBuilder.scala` | Shared converter from the six tokenized FEN fields to a validated `GameState`. Computes `inCheck` via `MoveValidator.isInCheck`. |
| `FenSerializer.scala` | `serialize(state: GameState): String` — emits the canonical FEN string for a game state. Halfmove/fullmove counters are emitted as `0 1` because `GameState` does not track them. |
| `PgnParser.scala` | Parses PGN (Portable Game Notation) move text into a list of moves. |
| `PgnSerializer.scala` | Serializes a game's move history into PGN format. |
| `JsonParser.scala` | Parses a JSON representation of game state. |
| `JsonSerializer.scala` | Serializes game state to JSON. |
| `JsonCodec.scala` | Combines `JsonParser` and `JsonSerializer` for round-trip JSON encoding. |

**Future:** The Phase 4 REST API will use this package to expose `GET /games/:id` (serializer) and `POST /games` with a FEN body (parser).

### `chess.controller`

Input handling and shared move-processing logic.

| File | Purpose |
|---|---|
| `GameController.scala` | Shared move-processing logic used by both TUI and web: `makeMove`, `undo`, `redo`, `claimDraw`. Orchestrates `GameService`, SAN serialization, session state updates, and repetition detection (`positionKey`, `countCurrentPosition`, `isFivefoldRepetition`). |
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
     → codec → model.rules → model
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
- [ADR 008 — Undo/redo via full replay from initial state](adr/008-undo-redo-via-replay.md)

## Future Integration Points

See [`docs/roadmap.md`](roadmap.md) for the full phased plan.

| Phase | Technology | Integration seam |
|-------|-----------|-----------------|
| 3 — Parsers (combinators / fastparse / regex) | `scala-parser-combinators`, `fastparse`, `scala.util.matching.Regex` | `chess.codec` package: three FEN parsers + serializer |
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
| scala-parser-combinators 2.4.0 | Parser combinators (used in `chess.codec`) |
| fastparse 3.1.1 | Macro-based parser library (used in `chess.codec`) |
| zio-test 2.1.24 | Test framework |
| sbt-scoverage 2.2.1 | Coverage instrumentation; build fails below 100% |
| sbt-scalafmt 2.5.2 | Code formatting; run `sbt scalafmtAll` after any change |
