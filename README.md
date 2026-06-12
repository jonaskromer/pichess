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
![Scala.js](https://img.shields.io/badge/web%20UI-Scala.js%20%2B%20Laminar-1c64f2?logo=scala&logoColor=white)
![k3s](https://img.shields.io/badge/deploy-k3s-326CE5?logo=kubernetes&logoColor=white)

**Metrics** &nbsp;&nbsp;
[![lines of code](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FSloppyPotato%2Fedd5d4dd1a82fef389f1118aeaf291f5%2Fraw%2Floc.json)](https://gist.github.com/SloppyPotato/edd5d4dd1a82fef389f1118aeaf291f5)
[![modules](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FSloppyPotato%2Fedd5d4dd1a82fef389f1118aeaf291f5%2Fraw%2Fmodules.json)](https://gist.github.com/SloppyPotato/edd5d4dd1a82fef389f1118aeaf291f5)
[![tests](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FSloppyPotato%2Fedd5d4dd1a82fef389f1118aeaf291f5%2Fraw%2Ftests.json)](https://gist.github.com/SloppyPotato/edd5d4dd1a82fef389f1118aeaf291f5)
[![tech debt](https://img.shields.io/endpoint?url=https%3A%2F%2Fgist.githubusercontent.com%2FSloppyPotato%2Fedd5d4dd1a82fef389f1118aeaf291f5%2Fraw%2Ftechdebt.json)](https://gist.github.com/SloppyPotato/edd5d4dd1a82fef389f1118aeaf291f5)
[![lichess blitz](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Flichess.org%2Fapi%2Fuser%2Fpichess-htwg&query=%24.perfs.blitz.rating&label=lichess%20blitz&logo=lichess&color=brightgreen)](https://lichess.org/@/pichess-htwg)
![last commit](https://img.shields.io/github/last-commit/jonaskromer/pichess?color=ff9bb3)

Welcome to **πChess** (pronounced like *peaches* in German)!

πChess is a chess platform written in **Scala 3** using **ZIO** throughout — from domain validation to HTTP serving. It's a microservice stack: a **gateway** (REST + Swagger + SSE + a Laminar/Scala.js web UI), a **game-service** (authoritative game state over **gRPC**, with an embedded **~2350-Elo NNUE engine** for vs-computer play), an event-sourced **repository** write-side, and a **lobby-service** for multiplayer — glued together with gRPC between services and **Kafka** as the event log. Persistence is pluggable across **Postgres / MongoDB / Redis / Cassandra**; two optional Kafka projections fan the event log out to **Neo4j** (opening tree) and **ClickHouse** (analytics); and every service ships **Prometheus / Grafana / Jaeger** observability. The same engine plays online as [**pichess-htwg**](https://lichess.org/@/pichess-htwg) on Lichess.

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
- 🍑 **[Bot & Engine](docs/bot.md)** — search + NNUE/hybrid evaluation, the Lichess client, and **how the bot's Elo is correctly measured** (UCI_Elo-anchored Stockfish). See also **[engine-levers.md](docs/engine-levers.md)** for the search/eval A/B history.
- 🍑 **[Persistence](docs/db-selection-report.md)** — the backend×cache×workload selection report behind the `mongo + redis` production default.
- 🍑 **[Deployment Plan](docs/deployment-plan.md)** — the (planned) k3s rollout to the HTWG VM.
- 🍑 **[Roadmap](docs/roadmap.md)** — the 14-phase evolution plan.

## 🛠️ Current Status

The 14-phase HTWG Software-Architecture plan (see the **[Roadmap](docs/roadmap.md)**) is essentially complete, and the engine work has continued well past it. Phases 1–11 are done; Phase 12 is theoretical; the Phase 13 "Spark" brief was met instead with a Kafka → ClickHouse analytics projection.

**What works:**

*Chess rules*
- Full move validation for every piece type — en passant, castling, pawn promotion, check, checkmate
- Every draw rule — stalemate, 50-move, insufficient material, threefold (claim), fivefold (automatic)
- Undo/redo, coordinate + SAN notation (with disambiguation), and FEN / PGN / JSON import-export

*Engine / bot*
- Alpha-beta search with a transposition table, quiescence, null-move pruning, SEE move ordering, singular + check extensions, and an iterative-deepening time budget
- **Hybrid HCE + NNUE evaluation** (Stockfish-distilled net) — measured at **~2350 Elo** against UCI_Elo-anchored Stockfish
- Weighted-random opening book + a Syzygy tablebase oracle for endgames
- Plays live on Lichess as [**pichess-htwg**](https://lichess.org/@/pichess-htwg); offline training + the Elo harness live in `bot-train` / `nnue-train`

*Platform*
- Microservices: **gateway** (REST + Swagger UI at `/docs` + SSE + web UI), **game-service** (zio-grpc; embeds the engine for vs-computer), **repository** (event-sourced write side), **lobby-service** (multiplayer invite flow)
- **Pluggable persistence** behind a DAO trait — Postgres (Slick), MongoDB, Redis, Cassandra, or in-memory, with an optional Redis cache decorator; the backend is chosen by a single env var (`PICHESS_BACKEND`)
- **Kafka event log** (`chess.game-events`, KRaft mode) consumed by the repository, plus two optional projections: opening tree → **Neo4j**, analytics → **ClickHouse**
- Browser GUI (Scala.js + Laminar) — drag-and-drop, promotion dialog, and live multi-client sync via SSE
- **Observability** on every service — Prometheus metrics, Grafana dashboards, Jaeger tracing
- **Performance harness** — cross-backend Gatling load tests, k6 (browser / gRPC / Kafka surfaces), JMH microbenchmarks, async-profiler
- **31 sbt modules** (`domain` + `api` cross-compile to JVM + JS) with **per-service** Docker images and layered packaging
- Typed error handling with `IO[GameError, A]` throughout, and a **100% statement-coverage gate** on JVM modules (generated / Kafka-Main / gRPC-Main paths excluded)
- CI on every push (test + coverage gate); tagged releases publish multi-arch images to **GHCR**

**What's deferred:**
- **k3s deployment** to the HTWG VM — manifests + a CI deploy job are planned, not yet built (see **[deployment-plan.md](docs/deployment-plan.md)**)
- **game-service restart resilience** — replay the Kafka topic on startup to rebuild in-memory state
- **Keycloak** access management — optional, deferred

---
*Built with pure functions, immutability, and plenty of 🍑.*
