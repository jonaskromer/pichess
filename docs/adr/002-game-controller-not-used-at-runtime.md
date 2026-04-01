# ADR 002 — GameController owns shared move-processing logic

## Status

Accepted (supersedes earlier decision where GameController was unused at runtime)

## Context

Both the TUI (`Main.tuiLoop`) and the web GUI (`WebController.handleMove`) need to process a move against the shared game session. The sequence is identical:

1. Read current session state
2. Call `GameService.makeMove` with the raw input
3. Compute SAN notation via `SanSerializer.toSan`
4. Update the `SubscriptionRef[SessionState]` with the new state and move log entry

Previously this logic was duplicated in both UIs. `GameController` existed as an unused pure utility.

## Decision

`GameController.makeMove` encapsulates the shared "apply a move to the session" logic. Both `Main` and `WebController` delegate to it:

```scala
object GameController:
  def makeMove(
      gs: GameService,
      session: SubscriptionRef[SessionState],
      rawInput: String
  ): IO[GameError, Unit]
```

- **TUI:** `GameController.makeMove(gs, session, raw).foldZIO(err => ..., _ => ...)`
- **Web:** extracts the move string from JSON, then calls `GameController.makeMove(gs, session, move)`

Quit routing remains in each UI — it is a transport concern, not a game concern.

## Consequences

**Benefits:**
- Move-processing logic lives in one place. Adding steps (e.g., check/checkmate detection, move validation feedback) requires a single change.
- Both UIs stay thin — they handle only transport-specific concerns (terminal I/O, HTTP request/response).
- `GameController` is fully testable with `SubscriptionRef` — no HTTP or console mocking needed.

**Trade-offs:**
- `GameController` depends on `SubscriptionRef[SessionState]`, tying it to the shared-state architecture. If the session model changes, the controller changes too.
