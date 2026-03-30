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

- `MoveValidator.scala` — validates a proposed move; returns `Either[String, Unit]`
- `Game.scala` — applies a validated move to produce a new `GameState`

For a rule that only blocks moves (e.g. check detection): add a guard in `MoveValidator.validate`.

For a rule that also changes board state after the move (e.g. castling, en passant): add the state-mutation logic in `Game.applyMove` / `Game.updatedBoard`.

Cover every new branch with tests in `MoveValidatorSpec` or `GameSpec` before the build will pass.

---

## Adding a New View

Views live in `chess.view` and are pure functions over `GameState`:

```scala
object JsonBoardView:
  def render(state: GameState): String = ???
```

No ZIO, no I/O. The view is called by the transport layer (`Main`, future HTTP routes, etc.).
