# Development Guide

> For the overall layer structure and package responsibilities, see [architecture.md](architecture.md).

## Prerequisites

- JDK 11+
- sbt 1.12.6

## Common Commands

| Command | Purpose |
|---|---|
| `sbt run` | Start the TUI chess game |
| `sbt test` | Run all tests |
| `sbt scalafmtAll` | Format all source files (required before committing) |
| `sbt coverage test coverageReport` | Run tests with coverage report |

Coverage is enforced at 100%. The build fails if any line is uncovered. `Main.scala` is explicitly excluded from coverage measurement.

---

## Workflow (TDD)

1. **Plan** — identify which layer the change belongs to (model, rules, controller, service, view, repository)
2. **Write tests first** — add specs in the corresponding `*Spec.scala` file
3. **Make them pass** — implement the minimum code needed
4. **Format** — run `sbt scalafmtAll`
5. **Verify coverage** — run `sbt coverage test coverageReport`

---

## Adding a New Repository Implementation

1. Create a class in `chess.repository` that extends `GameRepository`:
   ```scala
   final class PostgresGameRepository(...) extends GameRepository:
     def save(id: GameId, state: GameState): Task[Unit] = ???
     def load(id: GameId): Task[Option[GameState]] = ???
     def delete(id: GameId): Task[Unit] = ???
   ```
2. Expose it as a `ZLayer`:
   ```scala
   object PostgresGameRepository:
     val layer: TaskLayer[GameRepository] = ZLayer.fromFunction(...)
   ```
3. In `Main.scala`, replace `InMemoryGameRepository.layer` with `PostgresGameRepository.layer` — no other file changes needed.

---

## Adding a New Move Rule

> For the list of rules not yet implemented, see [game-rules.md — Not Yet Implemented](game-rules.md#not-yet-implemented).

All chess logic lives in `chess.model.rules`:

- `MoveValidator.scala` — validates a proposed move; returns `Either[GameError, Unit]`
- `Game.scala` — applies a validated move to produce a new `GameState`; also validates and applies promotion

For a rule that only blocks moves (e.g. check detection): add a guard in `MoveValidator.validate`.

For a rule that also changes board state after the move (e.g. castling, en passant, promotion): add the state-mutation logic in `Game.applyMove` / `Game.updatedBoard`.

Cover every new branch with tests in `MoveValidatorSpec` or `GameSpec` before the build will pass.

### Adding a New Notation Style

Notation resolvers live in `chess.notation` and implement `NotationResolver`:

```scala
object MyResolver extends NotationResolver:
  def parse(input: String, state: GameState): Option[Either[GameError, Move]] =
    // Return None if this resolver doesn't recognize the input
    // Return Some(Right(move)) on success
    // Return Some(Left(error)) if recognized but invalid
```

Register the new resolver in `MoveParser.resolvers` (order matters — first match wins).

---

## Adding a Parser (Phase 3)

Parsers live in `chess.codec` and extend `RegexParsers`:

```scala
import scala.util.parsing.combinator.RegexParsers

class FenParser extends RegexParsers:
  // terminals
  private def rank: Parser[Int] = """\d""".r ^^ (_.toInt)
  // ...

  // public API always returns Either
  def parse(input: String): Either[String, GameState] =
    parseAll(fenRule, input) match
      case Success(result, _)   => Right(result)
      case NoSuccess(msg, next) => Left(s"[${next.pos}] $msg")
```

Key rules:
- Extend `RegexParsers` (not bare `Parsers`)
- Always use `parseAll` (not `parse`) so unconsumed input is an error
- Public method returns `Either[String, T]` — never expose `ParseResult` directly
- Chain post-parse validation with for-comprehension on `Either`
- Dependency: `"org.scala-lang.modules" %% "scala-parser-combinators" % "2.1.1"`

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
