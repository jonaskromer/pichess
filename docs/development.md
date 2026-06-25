# Development Guide

> For the overall layer structure and package responsibilities, see [architecture.md](architecture.md). For load tests, microbenchmarks, profiling, metrics and tracing, see [performance.md](performance.md).

## Prerequisites

- JDK 17+ (the project compiles with Scala 3.8.2 against a JDK 17 baseline; Gatling's `--add-opens` settings in `build.sbt` assume JDK 17+ and the dev rig is verified on JDK 23)
- sbt 1.12.6
- Docker Desktop (for the integrated stack + the k6 perf surfaces)

## Common Commands

`make` (no args) prints the full menu. The high-traffic groups are:

**Pick a backend stack** (mutually exclusive — running one tears the others down)

| Command | Purpose |
|---|---|
| `make stack-postgres` / `stack-mongo` / `stack-redis` / `stack-cassandra` | Bring up the stack with `PICHESS_BACKEND=<name>` |
| `make stack-inmemory` | Stack with no DB — game state lives in-process |
| `make stack-status` | Show the active stack profile + running containers |
| `make stack-restart` | Re-up the last-selected stack (reads `.pichess-stack`) |
| `make stack-down` | Stop the active stack and clear the state file |

**Layer in profile addons** (additive — bring up alongside any running stack)

| Command | Brings up | URL targets |
|---|---|---|
| `make obs` / `obs-down` / `obs-status` | Prometheus + Grafana + Jaeger | `make grafana` / `prometheus` / `jaeger` open the UIs |
| `make opening` / `opening-down` | Kafka + opening-service + Neo4j | — |
| `make analytics` / `analytics-down` | Kafka + analytics-service + spark-analytics + kafka-exporter (no DB) | — |

> Note: `make opening` / `make analytics` bring up the consumers, but game-service only publishes if `KAFKA_BOOTSTRAP_SERVERS` was set at start time. For full publisher → consumer integration, use `make stack-<bk> EXTRA=opening` (or `analytics`). The analytics **dashboard lives in Grafana**, so add `obs` too: `make stack-<bk> EXTRA=analytics,obs` → Grafana on :3000 ("piChess — game analytics").

**Per-service iteration**

| Command | Purpose |
|---|---|
| `make build` | Rebuild all service Docker images (alias for `sbt dockerBuildAll`) |
| `make build-<svc>` | Rebuild one image — `gateway`, `game-service`, `repository`, `lobby-service`, `opening-service`, `analytics-service`, `tui` |
| `make dev-<svc>` | Rebuild + restart only that container (`--no-deps`) so the DBs and other services keep running |
| `make shell-<svc>` | Open a shell inside a running service container |
| `make logs` | Tail logs for every service in the stack |
| `make ps` | List running containers |
| `make tui` | Run an interactive TUI session against the gateway |

**DB shells**

| Command | Drops you into |
|---|---|
| `make psql` | postgres |
| `make mongo` | mongosh |
| `make redis-cli` | redis-cli |
| `make cqlsh` | cassandra |
| `make cypher` | neo4j (opening projection) |

**sbt-level commands** (run from the host, no Docker)

| Command | Purpose |
|---|---|
| `sbt test` | Run all tests across all modules |
| `sbt scalafmtAll` | Format all source files (required before committing) |
| `sbt coverage test coverageReport` | Run tests with coverage report |
| `sbt gameService/run` | Run game-service (gRPC :9000) on the host JVM |
| `sbt gateway/run` | Run gateway (HTTP :8090) on the host JVM |
| `sbt repository/run` | Run repository (REST :8091, optionally Kafka consumer) on the host |
| `sbt <svc>/Docker/publishLocal` | Build a single service's Docker image |

**Perf suite** — see [performance.md](performance.md) for the full reference.

| Command | Purpose |
|---|---|
| `make perf-all` | JMH bench + Gatling cross-backend + all three k6 surfaces, output rooted at one `perf-reports/<TS>/` |
| `make perf` | Cross-backend Gatling harness — `BACKENDS=…`, `MODE=…`, `OBS=…` |
| `make bench` | JMH microbench suite |
| `make k6-browser` / `k6-grpc` / `k6-kafka` | Individual k6 surfaces |
| `make k6` `SURFACES=…` | All k6 surfaces (default: browser only) |
| `make k6-build` | Build the custom k6 image (xk6-kafka + Chromium) |
| `make profile-async-cpu SERVICE=<svc>` | Attach async-profiler to a live container |

> **`sbt run` at the root is no longer wired** — the previous `app` monolith was split into multiple services (game-service, gateway, repository, lobby-service, opening-service, analytics-service). Use the per-service commands above. See [ADR 013](adr/013-deletion-of-app-module-and-sbt-run.md).

### Inner-loop env vars (host JVM)

| Service           | Env var                   | Default            |
|-------------------|---------------------------|--------------------|
| game-service      | `GRPC_PORT`               | `9000`             |
| game-service      | `KAFKA_BOOTSTRAP_SERVERS` | (unset → in-memory recorder, no Kafka required) |
| game-service      | `PICHESS_BACKEND`         | `postgres` (default; also `inmemory` / `mongo` / `redis` / `cassandra`) |
| game-service      | `PICHESS_CACHE`           | `redis` (default; set `none` to drop the `CachedGameRepository` decorator) |
| game-service      | `METRICS_PORT`            | `9102` |
| repository        | `REPOSITORY_PORT`         | `8091`             |
| repository        | `KAFKA_BOOTSTRAP_SERVERS` | (unset → HTTP-only, no consumer) |
| repository        | `KAFKA_CONSUMER_GROUP`    | `pichess-repository` |
| repository        | `PICHESS_BACKEND` / `PICHESS_CACHE` | same as game-service (shares `PersistenceLayers`) |
| repository        | `METRICS_PORT`            | `9103` |
| gateway           | `HTTP_PORT`               | `8090`             |
| gateway           | `GAME_SERVICE_GRPC`       | `localhost:9000`   |
| gateway           | `METRICS_PORT`            | `9101` |
| lobby-service     | `LOBBY_PORT`              | `8092` |
| lobby-service     | `METRICS_PORT`            | `9104` |
| opening-service   | `KAFKA_BOOTSTRAP_SERVERS` | (required when running) |
| opening-service   | `KAFKA_CONSUMER_GROUP`    | `pichess-opening` |
| opening-service   | `METRICS_PORT`            | `9105` |
| opening-service   | `PICHESS_NEO4J_URL`       | `bolt://localhost:7687` |
| opening-service   | `PICHESS_NEO4J_USER`      | `neo4j` |
| opening-service   | `PICHESS_NEO4J_PASSWORD`  | `password` |
| analytics-service | `ANALYTICS_PORT`          | `8093` |
| analytics-service | `KAFKA_BOOTSTRAP_SERVERS` | (required when running) |
| analytics-service | `KAFKA_CONSUMER_GROUP`    | `pichess-analytics` |
| analytics-service | `METRICS_PORT`            | `9106` |
| spark-analytics   | `KAFKA_BOOTSTRAP_SERVERS` | (required when running) |
| spark-analytics   | `PICHESS_SPARK_CHECKPOINT` | `/var/pichess/spark/analytics-sink` |
| tui               | `PICHESS_GATEWAY_URL`     | `http://localhost:8090` |
| tui               | `PICHESS_SESSION_ID`      | (random UUID minted per process) |
| tui               | `PICHESS_NICKNAME`        | `Anonymous` |
| bot-train         | `PICHESS_DUCKDB_PATH`     | `./chess-bot-training.duckdb` |
| **all services**  | `PICHESS_PROFILE`         | unset (`sampling` enables zio-profiling — requires a profile-tagged build, see [performance.md](performance.md) "Layer 3") |
| **all services**  | `TRACING_ENABLED`         | unset (truthy → live OTLP exporter to Jaeger at `OTEL_EXPORTER_OTLP_ENDPOINT`) |
| **all services**  | `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://jaeger:4317` (only read when `TRACING_ENABLED` is truthy) |
| **all services**  | `OTEL_SERVICE_NAME`       | (per-service default, e.g. `gateway`; override for multi-instance fan-out) |

### Multi-Project Tips

The project has 36 SBT sub-projects (run `sbt projects` for the full list; `domain` and `api` each show up twice there, as JVM + JS variants). To run commands against a single module, prefix with the module name: `sbt codec/test`, `sbt gateway/compile`, etc. `sbt test` runs tests across all modules.

Coverage is enforced at 100% on all JVM modules. The build fails if any line is uncovered. Scala.js modules (`web-ui`, `domain.js`, `api.js`) have coverage disabled since scoverage doesn't instrument JS output. The `proto` module has coverage disabled (generated code) and `KafkaGameEventProducer`/`KafkaGameEventConsumer`/`GameServiceMain`/`GatewayMain` are excluded via `coverageExcludedFiles` (they need a live broker / port to exercise; covered by docker-compose smoke tests instead). Use `scripts/check-coverage.py` after a `coverageReport` run to inspect uncovered lines per file.

### Troubleshooting

- **"Kafka not reachable"** — confirm `make ps` shows the kafka container as `healthy`. The healthcheck calls `kafka-topics --bootstrap-server localhost:9092 --list`; if it fails, check broker logs with `docker compose logs kafka`.
- **"gRPC handshake failure" from gateway** — game-service hasn't started yet, or `GAME_SERVICE_GRPC` points to the wrong target. The gateway retries on connect via the gRPC client; persistent failure usually means a port mismatch.
- **"Stale image after publishLocal"** — `docker compose up -d --no-deps --build <svc>` forces compose to recreate the container with the freshly published image.
- **`sbt run` errors with "No main class detected"** — that's the intended signal; use `make up` (or `sbt <svc>/run` for a host-JVM single service).

Tests use **zio-test** (`ZIOSpecDefault`). Each test spec is an `object` extending `ZIOSpecDefault` with a `def spec` that returns a `Spec` tree of `suite(...)` and `test(...)` blocks. Assertions use `assertTrue(...)`. Service/repository tests provide layers via `.provide(layer)` on the suite.

---

## Workflow (TDD)

1. **Plan** — identify which layer the change belongs to (model, rules, controller, service, view, repository)
2. **Write tests first** — add specs in the corresponding `*Spec.scala` file
3. **Make them pass** — implement the minimum code needed
4. **Format** — run `sbt scalafmtAll`
5. **Verify coverage** — run `sbt coverage test coverageReport`

### Bug fixes — reproducer-first

When fixing a bug, write a regression spec **before** touching production code:

1. **Reproduce the bug in a failing spec.** Name the test
   `regression: <summary>` and add a comment above it describing the symptom
   and (if known) the commit/issue that introduced the bug.
2. **Run it and confirm it fails for the right reason.** A spec that fails for
   the wrong reason is not a reproducer — rewrite until the failure matches
   the actual bug.
3. **Then fix the bug.** The regression test going green is the definition of
   "fixed".

#### Regression test policy

- All bug-reproducer tests must use the `regression:` prefix in the test name.
  Grep for `"regression:` to find them.
- **Never delete a `regression:` test.** If a refactor changes the shape of
  the code it covers, *adapt* the test to the new API. The historical bug it
  guards against is still real, and a passing regression test is the only
  evidence that the bug stays fixed across refactors.
- The only valid reason to remove a regression test is that the underlying
  logic no longer exists at all (e.g. the entire subsystem was deleted). If
  you remove one, explain why in the commit message.

---

## Adding a New Repository Implementation

The persistence layer is split into one module per backend:
`persistence/{api,contract,cache,postgres,mongo,redis,cassandra,runtime}`.
The `api` module defines the trait + the `Backend` enum + `BackendConfig`
(reads `PICHESS_BACKEND` and `PICHESS_CACHE`). The `runtime` module's
`PersistenceLayers` is the single switch every service Main uses; one
new backend = one new module + one new case in two switches.

1. **Create a module** for the new backend at `persistence/<name>/`. Mirror
   the shape of an existing one (e.g. `persistence/postgres/`): a `*Database` /
   `*ClientLayer` for the connection, a `*GameRepository` and `*LobbyRepository`
   each extending the traits from `persistence.api`. Wire it as an sbt
   subproject in `build.sbt` and add it to the `persistenceRuntime` dependency list.
2. **Implement the repository traits** (`GameRepository`, `LobbyRepository`):
   ```scala
   final class ClickhouseGameRepository(client: Client) extends GameRepository:
     def save(id: GameId, state: GameState): IO[GameError, Unit] = ???
     def load(id: GameId): IO[GameError, Option[GameState]] = ???
     def delete(id: GameId): IO[GameError, Unit] = ???
   ```
3. **Expose each repository as a `ZLayer`**:
   ```scala
   object ClickhouseGameRepository:
     val layer: URLayer[Client, GameRepository] = ZLayer.fromFunction(...)
   ```
4. **Add a `Backend.Clickhouse` case** in `persistence/api/.../Backend.scala`,
   parsed from the env value `clickhouse` in `BackendConfig`.
5. **Wire it into `PersistenceLayers`** in `persistence/runtime/.../PersistenceLayers.scala` —
   add a `case Backend.Clickhouse => …` arm to both `primaryGameRepository`
   and `primaryLobbyRepository`. No service-Main change is needed; all three
   service mains (`gameService`, `repository`, `lobbyService`) consume
   `PersistenceLayers.gameRepository(cfg)` / `lobbyRepository(cfg)` already.
6. **Add a compose profile** for the new datastore in `docker-compose.yml`
   (mirror the postgres / mongo / redis / cassandra blocks: profile name
   matches `PICHESS_BACKEND`, healthcheck waits on a real readiness probe).
7. **Add a `make stack-<name>`** target in `Makefile` next to the existing
   `stack-postgres` etc. Three-line copy-paste of the `_stack_up` macro.

The cache decorator (`PICHESS_CACHE=redis`) is orthogonal — `PersistenceLayers`
wraps any primary backend in `CachedGameRepository` when the cache env var is
set, so a new backend gets caching support for free.

---

## Docker

Nine services are Docker-packaged via sbt-native-packager. The full set:

| Service             | Container port | Module           | Role |
|---------------------|----------------|------------------|------|
| `gateway`           | 8090 (HTTP)    | `gateway`        | Public REST + SPA + SSE. Calls game-service over gRPC and lobby-service over HTTP. |
| `game-service`      | 9000 (gRPC)    | `game-service`   | Command handler + the canonical per-game state. Publishes `GameDomainEvent` to Kafka when configured. |
| `repository`        | 8091 (REST)    | `repository`     | Persistence write-side. Consumes `chess.game-events` and saves via `PersistenceLayers`. |
| `lobby-service`     | 8092 (REST)    | `lobby-service`  | Lobby management + invite-code flow. |
| `opening-service`   | (no HTTP)      | `opening-service`| Kafka consumer → Neo4j opening-tree projection. |
| `spark-analytics`   | (no HTTP)      | `spark-analytics`| Spark speed layer: `chess.game-events` → sessionize → `chess.analytics`. |
| `analytics-service` | 8093 (REST)    | `analytics-service` | Kafka consumer (`chess.game-events` + `chess.analytics`) → zio-metrics for Grafana (no DB). |
| `tui-service`       | (no HTTP)      | `tui`            | Headless control surface; spawn via `make tui`. |
| `bot-tournament`    | (metrics :9107)| `bot-tournament` | Plays an external NowChess tournament server (gateway-signalled). Compose `tournament` profile / k8s `full` overlay. |

Each service also exposes Prometheus metrics on a dedicated port (9101–9106) regardless of profile — see the "Inner-loop env vars" table above for `METRICS_PORT` defaults.

**Image rebuilds**:

```bash
make build                       # all images at once (sbt dockerBuildAll)
make build-<svc>                 # one image
sbt <svc>/Docker/publishLocal    # equivalent to make build-<svc>
```

**Stack bring-up** — see "Common Commands" above for the full menu. The short answer:

```bash
make stack-postgres              # one backend, no extras
make stack-postgres EXTRA=obs    # add Prometheus / Grafana / Jaeger
make stack-postgres EXTRA=opening,analytics,obs
                                 # everything — Kafka, both projections, obs
```

The persistence selection happens via `PICHESS_BACKEND` (read by every service Main through `BackendConfig`); the compose `--profile <bk>` flag controls which DB container starts. Backend service code is shared across all services via the `persistence` modules — see "Adding a New Repository Implementation" above.

`docker-compose.yml` at the project root wires everything together. Profiles are used for backend selection (`postgres` / `mongo` / `redis` / `cassandra`) and for additive features (`obs` / `opening` / `analytics` / `tui` / `k6`).

---

## Adding a New Move Rule

> For the full catalogue of rules already implemented, see [game-rules.md — Implemented Rules](game-rules.md#implemented-rules).

All chess logic lives in `chess.model.rules`:

- `MoveValidator.scala` — validates a proposed move; returns `IO[GameError, Unit]`
- `Game.scala` — applies a validated move to produce a new `GameState`; also validates and applies promotion

For a rule that only blocks moves (e.g. check detection): add a guard in `MoveValidator.validate`.

For a rule that also changes board state after the move (e.g. castling, en passant, promotion): add the state-mutation logic in `Game.applyMove` / `Game.updatedBoard`.

Cover every new branch with tests in `MoveValidatorSpec` or `GameSpec` before the build will pass.

### Adding a New Notation Style

Notation resolvers live in `chess.notation` and implement `NotationResolver`:

```scala
object MyResolver extends NotationResolver:
  def parse(input: String, state: GameState): IO[GameError, Option[Move]] =
    // Return ZIO.succeed(None) if this resolver doesn't recognize the input
    // Return ZIO.succeed(Some(move)) on success
    // Return ZIO.fail(error) if recognized but invalid
```

Register the new resolver in `MoveParser.resolvers` (order matters — first match wins).

---

## Adding a Parser (Phase 3)

Parsers live in `chess.codec` and implement the `FenParser` trait:

```scala
trait FenParser:
  def parse(input: String): IO[GameError, GameState]
```

Three reference implementations exist side-by-side, one per parsing technique requested by SA-03:

- `FenParserCombinator` — `scala-parser-combinators` / `RegexParsers`
- `FenParserFastParse` — `fastparse` macro-based combinators
- `FenParserRegex` — `scala.util.matching.Regex`, no external library

All three tokenize into six raw FEN fields and then call **`FenBuilder.build`** for semantic validation. Add new parsers by following the same split: a thin grammar that produces token strings + a shared builder that converts tokens into the domain model.

Key rules:

- Public method returns `IO[GameError, T]` — never expose `ParseResult` directly.
- Combinator-style parsers must use `parseAll` (not `parse`) so trailing input is an error.
- Match the parser result against `case ns: NoSuccess` (type binding), not `case NoSuccess(msg, next)` (extractor) — Scala 3's exhaustiveness checker only sees the first form as covering both `Failure` and `Error`.
- For shared validation across parser implementations, factor it into a builder object so all parsers stay observationally equivalent.
- New parsers must add a `behaviors` row in `FenParserBehaviors.scala` and a per-implementation spec object that calls `FenParserBehaviors.behaviors(parser)`.

### JSON Codec

`JsonCodec` provides auto-derived `zio-json` codecs for `Piece`, `Board`, `CastlingRights`, `GameStatus`, and `GameState`. Enums are encoded as their case names (`"white"`, `"king"`, `"stalemate"`); positions are encoded as algebraic strings (`"e4"`); the board becomes a `Position → Piece` map. `JsonSerializer` / `JsonParser` are thin facades over the derived codecs.

Two derived fields are deliberately *not* trusted from the wire format:

- `inCheck` is recomputed on decode via `MoveValidator.isInCheck` — the wire field is overwritten.
- `halfmoveClock` and `fullmoveNumber` are optional and default to `0` / `1` so older snapshots stay loadable.

See [ADR 009](adr/009-recompute-derived-state-on-import.md) for the reasoning. `JsonCodecSpec` includes cross-validation tests that parse the same position from both FEN and JSON and assert the resulting `GameState` is identical.

### PGN Codec

`PgnSerializer` exports a move log and game status to PGN format with standard headers. `PgnParser` imports PGN by parsing headers, extracting SAN moves from the movetext (stripping comments, NAGs, move numbers, and result tokens), then replaying each move through `MoveParser` and `Game.applyMove`. The parser also supports a `[FEN "..."]` header for custom start positions.

Dependencies (already in `build.sbt`):

```scala
"org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0"
"com.lihaoyi"            %% "fastparse"                % "3.1.1"
```

---

## Adding a REST Route

The REST API lives in the `gateway` module (`WebController.scala`). Endpoint contracts are described with **Tapir** in the `api` module (`Endpoints.scala`), and wire DTOs live in `BoardStateDto.scala`. Both are cross-compiled JVM + JS so the Scala.js web-ui shares the same types.

To add a new endpoint:

1. Define the Tapir endpoint in `api/src/main/scala/chess/api/Endpoints.scala`:
   ```scala
   val myEndpoint = baseEndpoint.get
     .in("api" / "my-thing")
     .out(jsonBody[MyResponse])
   ```
2. Add any new DTOs to `BoardStateDto.scala` with `@jsonMemberNames(SnakeCase)` and derived codecs.
3. Implement the server logic in `gateway/src/main/scala/chess/controller/WebController.scala`:
   ```scala
   Endpoints.myEndpoint.zServerLogic { _ =>
     // delegate to GameController / GameService
   }
   ```
4. The endpoint automatically appears in the Swagger UI at `/docs`.

SSE (`/api/games/{id}/events`) is the one endpoint implemented as raw zio-http (not Tapir) because Tapir's typed model doesn't fit streaming.

URL design rules (from lecture):
- **Nouns not verbs** — `/games` not `/getGame`
- **Plural for collections**, singular instance via ID
- Routes are a view layer — they call `GameService`, contain no domain logic

---

## Adding a New View

Views live in the `chess.view` package (split across `domain`, `tui`, and `gateway` modules) and are pure functions over `GameState`:

```scala
object JsonBoardView:
  def render(state: GameState): String = ???
```

No ZIO, no I/O. Place the view in the module that uses it — `tui` for terminal views, `gateway` for web views. `PieceUnicode` is in `domain` so it can be shared across JVM and JS.
