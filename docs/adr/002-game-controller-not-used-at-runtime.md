# ADR 002 — GameController exists but is not used at runtime

## Status

Accepted

## Context

`GameController` is a pure function that routes `"quit"` → `None` and delegates move strings to `MoveParser` + `Game`. It was written and fully tested as a utility combinator.

At runtime, `Main.scala` handles `"quit"` via direct pattern matching, and move parsing is delegated to `GameService` (which calls `MoveParser` directly). `GameController` is therefore not on the hot path.

## Decision

Keep `GameController` as a tested pure utility. Do not wire it into `Main` or `GameServiceLive`.

## Consequences

**Why not use it in Main?**
`"quit"` is a TUI concern, not a domain or service concern. Routing it through `GameController` would leak UI-level semantics into a layer meant to be transport-agnostic. An HTTP handler, for example, would never send `"quit"` as a move string.

**Why keep it at all?**
- It is fully covered by tests and has no maintenance cost.
- It remains available as a convenience combinator for future TUI variants or CLI tools that want a single-function interface over `(GameState, String) => Option[Either[String, GameState]]`.
- Deleting tested, passing code provides no benefit.
