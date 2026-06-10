# <img src="gateway/src/main/resources/web/peach.svg" alt="🍑" width="32" /> piChess

**Build** &nbsp;&nbsp;
[![test](https://github.com/jonaskromer/pichess/actions/workflows/test.yml/badge.svg)](https://github.com/jonaskromer/pichess/actions/workflows/test.yml)
[![release](https://github.com/jonaskromer/pichess/actions/workflows/release.yml/badge.svg)](https://github.com/jonaskromer/pichess/actions/workflows/release.yml)
[![latest release](https://img.shields.io/github/v/release/jonaskromer/pichess?label=latest%20release&sort=semver&color=ff9bb3&logo=github)](https://github.com/jonaskromer/pichess/releases)
![coverage](https://img.shields.io/badge/coverage-100%25-brightgreen)

**Stack** &nbsp;&nbsp;
![Scala](https://img.shields.io/badge/Scala-3.8-DC322F?logo=scala&logoColor=white)
![ZIO](https://img.shields.io/badge/ZIO-throughout-7B2FBE)
![gRPC](https://img.shields.io/badge/gRPC-inter--service-244c5a?logo=grpc&logoColor=white)
![Kafka](https://img.shields.io/badge/Kafka-event%20log-231F20?logo=apachekafka)
![Docker](https://img.shields.io/badge/images-multi--arch-2496ED?logo=docker&logoColor=white)
![k3s](https://img.shields.io/badge/deploy-k3s-326CE5?logo=kubernetes&logoColor=white)

**Metrics** &nbsp;&nbsp;
[![lines of code](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FSloppyPotato%2Fedd5d4dd1a82fef389f1118aeaf291f5%2Fraw%2Floc.json)](https://gist.github.com/SloppyPotato/edd5d4dd1a82fef389f1118aeaf291f5)
[![modules](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FSloppyPotato%2Fedd5d4dd1a82fef389f1118aeaf291f5%2Fraw%2Fmodules.json)](https://gist.github.com/SloppyPotato/edd5d4dd1a82fef389f1118aeaf291f5)
[![tech debt](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FSloppyPotato%2Fedd5d4dd1a82fef389f1118aeaf291f5%2Fraw%2Ftechdebt.json)](https://gist.github.com/SloppyPotato/edd5d4dd1a82fef389f1118aeaf291f5)
[![lichess blitz](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Flichess.org%2Fapi%2Fuser%2Fpichess-htwg&query=%24.perfs.blitz.rating&label=lichess%20blitz&logo=lichess&color=brightgreen)](https://lichess.org/@/pichess-htwg)
![last commit](https://img.shields.io/github/last-commit/jonaskromer/pichess?color=ff9bb3)

Welcome to **πChess** (pronounced like *peaches* in German)!

πChess is a chess game written in **Scala 3** using **ZIO** throughout — from domain validation to HTTP serving. The architecture is a four-container microservice setup (gateway, game-service, repository, kafka) with **gRPC** between services, **Kafka** as the event log, and a Laminar/Scala.js web UI served from the gateway.

## 🚀 Getting Started

Ensure you have Java, `sbt`, and Docker installed.

### Integrated stack (Docker, prod-shaped)

```bash
make build && make up
```

Builds every service image then spins up the full stack (DBs, Kafka, services). Browse [http://localhost:8090](http://localhost:8090) for the web UI. Repository REST is on `:8091`; lobby-service REST is on `:8092`; analytics REST is on `:8093`; game-service gRPC is on `:9000`.

`make` (no args) lists every available target — `up`, `down`, `logs`, `psql` / `mongo` / `redis-cli` / `cqlsh` / `cypher` / `clickhouse-cli`, etc.

### Single-service rebuild after a code change

```bash
make dev-gateway        # also: dev-game-service | dev-repository | dev-lobby-service
                        #       dev-opening-service | dev-analytics-service
```

Rebuilds only the touched service's Docker image and restarts that container with `--no-deps`. Layered images mean a one-file edit only invalidates the small project-jar layer, so wall-clock should be under ~20s.

### Inner loop (host JVM, only Kafka in Docker)

```bash
docker compose up -d kafka
# In three terminals:
GRPC_PORT=9000 KAFKA_BOOTSTRAP_SERVERS=localhost:9092         sbt gameService/run
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 REPOSITORY_PORT=8091   sbt repository/run
HTTP_PORT=8090 GAME_SERVICE_GRPC=localhost:9000               sbt gateway/run
```

> **Note:** `sbt run` at the project root is no longer wired — the previous monolithic `app` module was split into the three services above. Use `sbt <svc>/run` or the `make` targets.

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
- 🍑 **[Performance & Profiling](docs/performance.md)** — Gatling, JMH, async-profiler, zio-profiling, Prometheus, Grafana, Jaeger, and the `make perf` harness.
- 🍑 **[Bot & Engine](docs/bot.md)** — search + NNUE/hybrid evaluation, the Lichess client, and **how the bot's Elo is correctly measured** (UCI_Elo-anchored Stockfish).
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
