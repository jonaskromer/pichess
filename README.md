# πChess 🍑

Welcome to **πChess** (pronounced like *peaches* in German)! 

πChess is a console-based chess game written in **pure Scala 3** using a strict **functional programming** style and **ZIO**. 

This project serves as a foundation for a layered architecture that will progressively evolve through multiple university lecture phases. Starting as a humble Text User Interface (TUI) game, it is designed to seamlessly scale into a modern, reactive system featuring HTTP APIs, persistence (MongoDB/PostgreSQL), WebSocket streams, and Kafka event publishing—all without mutating the core domain model.

## 🚀 Getting Started

Ensure you have Java and `sbt` installed on your machine.

To run the game locally:
```bash
sbt run
```

To run the tests and verify the strict 100% test coverage:
```bash
sbt clean coverage test coverageReport
```

## 📖 Documentation

Dive deeper into the project structure, our development workflow, and where the project is heading next:

- 🍑 **[Game Rules](docs/game-rules.md)**: A breakdown of the currently implemented chess piece mechanics, move notations, and missing rules.
- 🍑 **[Architecture](docs/architecture.md)**: Details on the pure functional domain layers, dependency injection via ZLayer, and ADRs.
- 🍑 **[Development Workflow](docs/development.md)**: Guidelines on our strict TDD rules, code formatting, and the `sbt` pipeline.
- 🍑 **[Roadmap](docs/roadmap.md)**: The 10-phase evolution plan of this project, taking it from a TUI game to a fully-distributed reactive microservice architecture.

## 🛠️ Current Status (Phase 1)

Currently, the project has completed **Phase 1: TUI Chess**. You can jump into `sbt run` and play a game right inside your console with piece validation, en passant, and in-memory persistence! Up next is Phase 2 to implement the missing chess rules (check, checkmate, castling, and draw conditions).

---
*Built with pure functions, immutability, and plenty of 🍑.*
