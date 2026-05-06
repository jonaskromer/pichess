# Development Guide

> For the overall layer structure and package responsibilities, see [architecture.md](architecture.md).

## Prerequisites

- JDK 11+
- sbt 1.12.6

## Common Commands

| Command | Purpose |
|---|---|
| `./scripts/dev-up.sh` | Build + start the integrated stack (kafka, game-service, repository, gateway) |
| `./scripts/dev-up.sh gateway` | Rebuild + restart only the gateway image (also: `game-service`, `repository`) |
| `./scripts/dev-logs.sh` | Tail logs for all services (or pass service names to filter) |
| `sbt test` | Run all tests across all modules |
| `sbt scalafmtAll` | Format all source files (required before committing) |
| `sbt coverage test coverageReport` | Run tests with coverage report |
| `sbt gameService/run` | Run game-service (gRPC :9000) on the host JVM |
| `sbt gateway/run` | Run gateway (HTTP :8090) on the host JVM |
| `sbt repository/run` | Run repository (REST :8091, optionally Kafka consumer) on the host |
| `sbt <svc>/Docker/publishLocal` | Build a single service's Docker image |
| `docker compose up` | Start the full stack |

> **`sbt run` at the root is no longer wired** — the previous `app` monolith was split into three services. Use the per-service commands above. See [ADR 013](adr/013-deletion-of-app-module-and-sbt-run.md).

### Inner-loop env vars (host JVM)

| Service       | Env var                  | Default            |
|---------------|--------------------------|--------------------|
| game-service  | `GRPC_PORT`              | `9000`             |
| game-service  | `KAFKA_BOOTSTRAP_SERVERS`| (unset → in-memory recorder, no Kafka required) |
| repository    | `REPOSITORY_PORT`        | `8091`             |
| repository    | `KAFKA_BOOTSTRAP_SERVERS`| (unset → HTTP-only, no consumer) |
| repository    | `KAFKA_CONSUMER_GROUP`   | `pichess-repository` |
| gateway       | `HTTP_PORT`              | `8090`             |
| gateway       | `GAME_SERVICE_GRPC`      | `localhost:9000`   |

### Multi-Project Tips

The project has 14 SBT sub-projects. To run commands against a single module, prefix with the module name: `sbt codec/test`, `sbt gateway/compile`, etc. `sbt test` runs tests across all modules.

Coverage is enforced at 100% on all JVM modules. The build fails if any line is uncovered. Scala.js modules (`web-ui`, `domain.js`, `api.js`) have coverage disabled since scoverage doesn't instrument JS output. The `proto` module has coverage disabled (generated code) and `KafkaGameEventProducer`/`KafkaGameEventConsumer`/`GameServiceMain`/`GatewayMain` are excluded via `coverageExcludedFiles` (they need a live broker / port to exercise; covered by docker-compose smoke tests instead). Use `scripts/check-coverage.py` after a `coverageReport` run to inspect uncovered lines per file.

### Troubleshooting

- **"Kafka not reachable"** — confirm `docker compose ps kafka` shows `healthy`. The healthcheck calls `kafka-topics --bootstrap-server localhost:9092 --list`; if it fails, check broker logs with `./scripts/dev-logs.sh kafka`.
- **"gRPC handshake failure" from gateway** — game-service hasn't started yet, or `GAME_SERVICE_GRPC` points to the wrong target. The gateway retries on connect via the gRPC client; persistent failure usually means a port mismatch.
- **"Stale image after publishLocal"** — `docker compose up -d --no-deps --build <svc>` forces compose to recreate the container with the freshly published image.
- **`sbt run` errors with "No main class detected"** — that's the intended signal; use `./scripts/dev-up.sh` or `sbt <svc>/run`.

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

1. Create a class in the `repository` module (`repository/src/main/scala/chess/repository/`) that extends `GameRepository`:
   ```scala
   final class PostgresGameRepository(...) extends GameRepository:
     def save(id: GameId, state: GameState): IO[GameError, Unit] = ???
     def load(id: GameId): IO[GameError, Option[GameState]] = ???
     def delete(id: GameId): IO[GameError, Unit] = ???
   ```
2. Expose it as a `ZLayer`:
   ```scala
   object PostgresGameRepository:
     val layer: URLayer[DataSource, GameRepository] = ZLayer.fromFunction(...)
   ```
3. In `app/src/main/scala/chess/Main.scala`, replace the repository layer selection — no other file changes needed. The existing `Main` already selects between `InMemoryGameRepository` and `HttpGameRepository` based on the `REPOSITORY_URL` env var; add a third branch or replace one.

---

## Docker

Two modules are Docker-packaged via sbt-native-packager: `app` (port 8090) and `repository` (port 8091).

```bash
sbt Docker/publishLocal          # build both images
docker compose up                # run both microservices
```

When `REPOSITORY_URL` is set (e.g. `http://repository:8091` in docker-compose), the `app` container uses `HttpGameRepository` to call the `repository` container over REST. Without it, `app` falls back to `InMemoryGameRepository`.

The `docker-compose.yml` at the project root wires both services together.

---

## Adding a New Move Rule

> For the list of rules not yet implemented, see [game-rules.md — Not Yet Implemented](game-rules.md#not-yet-implemented).

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
  def parse(input: String): Either[String, GameState]
```

Three reference implementations exist side-by-side, one per parsing technique requested by SA-03:

- `FenParserCombinator` — `scala-parser-combinators` / `RegexParsers`
- `FenParserFastParse` — `fastparse` macro-based combinators
- `FenParserRegex` — `scala.util.matching.Regex`, no external library

All three tokenize into six raw FEN fields and then call **`FenBuilder.build`** for semantic validation. Add new parsers by following the same split: a thin grammar that produces token strings + a shared builder that converts tokens into the domain model.

Key rules:

- Public method returns `Either[String, T]` — never expose `ParseResult` directly.
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

SSE (`/api/events`) is the one endpoint implemented as raw zio-http (not Tapir) because Tapir's typed model doesn't fit streaming.

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
