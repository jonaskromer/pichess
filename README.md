# πChess 🍑

Welcome to **πChess** (pronounced like *peaches* in German)! 

πChess is a chess game written in **Scala 3** using **ZIO** throughout — from domain validation to HTTP serving. It features a terminal TUI and a browser-based web GUI that stay in sync via `SubscriptionRef` and Server-Sent Events.

This project serves as a foundation for a layered architecture that will progressively evolve through multiple university lecture phases. Starting as a TUI game, it is designed to seamlessly scale into a modern, reactive system featuring HTTP APIs, persistence (MongoDB/PostgreSQL), reactive streams, and Kafka event publishing — all without mutating the core domain model.

## 🚀 Getting Started

Ensure you have Java and `sbt` installed on your machine.

To run the game locally:
```bash
sbt run
```

This starts both the TUI in the terminal and a web GUI at [http://localhost:8090](http://localhost:8090). Moves made in either UI are instantly reflected in the other.

To run the tests and verify the strict 100% test coverage:
```bash
sbt clean coverage test coverageReport
```

## 📖 Documentation

Dive deeper into the project structure, our development workflow, and where the project is heading next:

- 🍑 **[Game Rules](docs/game-rules.md)**: A breakdown of the currently implemented chess piece mechanics, move notations, and missing rules.
- 🍑 **[Architecture](docs/architecture.md)**: Details on the layered architecture, ZIO effects, dependency injection via ZLayer, and ADRs.
- 🍑 **[Development Workflow](docs/development.md)**: Guidelines on our strict TDD rules, code formatting, and the `sbt` pipeline.
- 🍑 **[Roadmap](docs/roadmap.md)**: The 14-phase evolution plan of this project, taking it from a TUI game to a fully-distributed reactive microservice architecture.

## 🛠️ Current Status

Phases 1 (TUI Chess), 2 (Functional Style), and 3 (Parser Combinators — three FEN parsers + JSON + PGN codecs) are complete. The web GUI from Phase 7 was built ahead of schedule. **Phase 4 (REST API)** is the next milestone.

**What works:**
- Full piece movement validation (all piece types)
- En passant, pawn promotion, check detection, castling, and checkmate
- Stalemate, 50-move draw rule, insufficient material, threefold repetition (claim), and fivefold repetition (automatic)
- Undo/redo support
- Coordinate notation and Standard Algebraic Notation (SAN) with disambiguation
- FEN, PGN, and JSON codecs for game state import/export
- ANSI-colored TUI with board flipping and move log
- Browser GUI with drag-and-drop, promotion dialog, and live sync via SSE
- Typed error handling with `IO[GameError, A]` throughout
- 100% test coverage with zio-test

**What's next:** REST API (Phase 4) on top of the FEN codec.

---
*Built with pure functions, immutability, and plenty of 🍑.*
