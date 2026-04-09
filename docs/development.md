# Development Guide

> For the overall layer structure and package responsibilities, see [architecture.md](architecture.md).

## Prerequisites

- JDK 11+
- sbt 1.12.6

## Common Commands

| Command | Purpose |
|---|---|
| `sbt run` | Start TUI + GUI (opens browser on port 8090) |
| `sbt "run --headless"` | Start TUI only (no web server, no browser) |
| `sbt test` | Run all tests |
| `sbt scalafmtAll` | Format all source files (required before committing) |
| `sbt coverage test coverageReport` | Run tests with coverage report |

Coverage is enforced at 100%. The build fails if any line is uncovered. `Main.scala` and `WebController.scala` are excluded from coverage measurement.

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

1. Create a class in `chess.repository` that extends `GameRepository`:
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
3. In `Main.scala`, replace `InMemoryGameRepository.layer` with `PostgresGameRepository.layer` — no other file changes needed.

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

`JsonSerializer` and `JsonParser` provide a human-readable JSON representation of `GameState`. The JSON format uses plain-English values (e.g. `"white rook"`) so positions are easy to verify by hand. `JsonCodecSpec` includes cross-validation tests that parse the same position from both FEN and JSON and assert the resulting `GameState` is identical.

### PGN Codec

`PgnSerializer` exports a move log and game status to PGN format with standard headers. `PgnParser` imports PGN by parsing headers, extracting SAN moves from the movetext (stripping comments, NAGs, move numbers, and result tokens), then replaying each move through `MoveParser` and `Game.applyMove`. The parser also supports a `[FEN "..."]` header for custom start positions.

Dependencies (already in `build.sbt`):

```scala
"org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0"
"com.lihaoyi"            %% "fastparse"                % "3.1.1"
```

---

## Adding a REST Route (Phase 4)

Routes live in `chess.http` and use the Akka HTTP Routing DSL:

```scala
import akka.http.scaladsl.server.Directives._

val gameRoutes =
  path("games") {
    post { /* GameService.newGame() */ }
  } ~
  path("games" / Segment) { id =>
    get    { /* GameService.getState(id) */ } ~
    delete { /* terminate game */ }
  } ~
  path("games" / Segment / "moves") { id =>
    post {
      entity(as[String]) { input =>
        /* GameService.makeMove(id, input) */
      }
    }
  }
```

URL design rules (from lecture):
- **Nouns not verbs** — `/games` not `/getGame`
- **Plural for collections**, singular instance via ID
- **Two URLs per resource**: `/games` (collection) and `/games/:id` (item)
- Routes are a view layer — they call `GameService`, contain no domain logic

---

## Adding a New View

Views live in `chess.view` and are pure functions over `GameState`:

```scala
object JsonBoardView:
  def render(state: GameState): String = ???
```

No ZIO, no I/O. The view is called by the transport layer (`Main`, future HTTP routes, etc.).
