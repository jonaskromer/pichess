# <img src="gateway/src/main/resources/web/peach.svg" alt="🍑" width="32" /> πChess

Welcome to **πChess** (pronounced like *peaches* in German)!

πChess is a chess game written in **Scala 3** using **ZIO** throughout — from domain validation to HTTP serving. The architecture is a four-container microservice setup (gateway, game-service, repository, kafka) with **gRPC** between services, **Kafka** as the event log, and a Laminar/Scala.js web UI served from the gateway.

## 🚀 Getting Started

Ensure you have Java, `sbt`, and Docker installed.

### Integrated stack (Docker, prod-shaped)

```bash
./scripts/dev-up.sh
```

Spins up `kafka`, `game-service`, `repository`, and `gateway`. Browse [http://localhost:8090](http://localhost:8090) for the web UI. Repository REST is on `:8091`; game-service gRPC is on `:9000`.

### Single-service rebuild after a code change

```bash
./scripts/dev-up.sh gateway        # also: game-service | repository
```

Rebuilds only the touched service's Docker image and restarts that container. Layered images mean a one-file edit only invalidates the small project-jar layer, so wall-clock should be under ~20s.

### Inner loop (host JVM, only Kafka in Docker)

```bash
docker compose up -d kafka
# In three terminals:
GRPC_PORT=9000 KAFKA_BOOTSTRAP_SERVERS=localhost:9092         sbt gameService/run
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 REPOSITORY_PORT=8091   sbt repository/run
HTTP_PORT=8090 GAME_SERVICE_GRPC=localhost:9000               sbt gateway/run
```

> **Note:** `sbt run` at the project root is no longer wired — the previous monolithic `app` module was split into the three services above. Use `sbt <svc>/run` or the dev scripts.

### Tests + coverage

```bash
sbt test                                    # full suite
sbt clean coverage test coverageReport      # 100% coverage gate on JVM modules
```

Kafka- and gRPC-server-Main code is excluded from unit coverage (it needs a live broker / port to exercise) and verified instead by docker-compose smoke tests.

## 📖 Documentation

- 🍑 **[Game Rules](docs/game-rules.md)** — implemented chess mechanics, move notations.
- 🍑 **[Architecture](docs/architecture.md)** — the microservice graph, event log, gRPC contract, ADRs.
- 🍑 **[Development Workflow](docs/development.md)** — TDD rules, sbt pipeline, troubleshooting.
- 🍑 **[Roadmap](docs/roadmap.md)** — the 14-phase evolution plan.

## 🛠️ Current Status

Phases 1 (TUI Chess), 2 (Functional Style), 3 (Parser Combinators), 4 (REST API), 5 (Microservices + Docker), 7 (Web GUI), and 11 (Kafka event log) are complete. **Phase 6 (Slick / PostgreSQL)** is the next milestone — a persistent `GameRepository` backing the existing REST + Kafka-consumer surface.

**What works:**
- Full piece movement validation (all piece types)
- En passant, pawn promotion, check detection, castling, and checkmate
- Stalemate, 50-move draw rule, insufficient material, threefold repetition (claim), and fivefold repetition (automatic)
- Undo/redo support
- Coordinate notation and Standard Algebraic Notation (SAN) with disambiguation
- FEN, PGN, and JSON codecs for game state import/export
- Browser GUI (Scala.js + Laminar) with drag-and-drop, promotion dialog, and live sync via SSE (fed by gRPC server-stream from gameService)
- REST API with Tapir endpoint contracts + Swagger UI at `/docs`
- gRPC contract (zio-grpc) between gateway and game-service
- Kafka event log (`chess.game-events`, KRaft mode); repository persists asynchronously by consuming the topic
- SBT multi-project build (14 modules) with **per-service** Docker images and layered packaging
- Typed error handling with `IO[GameError, A]` throughout
- 100% test coverage on JVM modules (Kafka/gRPC-Main paths excluded)

**What's deferred:**
- TUI rewrite to talk to the gateway over REST (currently the `tui` module is a parser-only library; runtime is documented future work).
- gameService restart resilience (replay state from Kafka on startup).

---
*Built with pure functions, immutability, and plenty of 🍑.*
