# ADR 008 — Undo/redo via state history

## Status

Accepted (supersedes earlier replay-from-initial approach)

## Context

The game needs undo and redo support, and draw rules (threefold/fivefold repetition) need access to the full position history. Several approaches exist:

1. **Inverse operations** — define a reverse for every move type. Undo applies the inverse. Memory-efficient but complex: en passant restores a captured pawn *and* the en-passant target square; castling must reverse both king and rook; promotion must demote back to a pawn. Every new rule adds a new inverse, and a bug in any inverse silently corrupts the board.
2. **Replay from initial state** — store the initial `GameState` plus an ordered list of `Move`s. Undo drops the last move and replays the remaining list through `Game.applyMove`. Simple, but O(n) per undo, and computing position history for repetition detection also requires O(n) replay.
3. **State history** — store the initial `GameState` plus a list of `(Move, GameState)` pairs. Undo and redo are O(1) stack operations. Position history is directly available for repetition detection.

An earlier version of this project used approach 2 (replay). When threefold/fivefold repetition rules were added, both undo and repetition detection required replaying the move list — two separate O(n) operations with the same root cause: intermediate states weren't stored. The state history approach eliminates both costs.

## Decision

Use a state history stack. The domain types are:

```scala
case class GameSnapshot(
    gameId: GameId,
    initialState: GameState,
    history: List[(Move, GameState)] = Nil,     // newest first
    redoStack: List[(Move, GameState)] = Nil
):
  def state: GameState = history.headOption.map(_._2).getOrElse(initialState)
  def moves: List[Move] = history.reverse.map(_._1)
```

- **Make a move:** prepend `(move, newState)` to `history`, clear `redoStack`.
- **Undo:** pop the head of `history`, push it onto `redoStack`. The previous state is the new head (or `initialState` if history is now empty). O(1).
- **Redo:** pop the head of `redoStack`, push it onto `history`. O(1).
- **Position history** (for repetition detection): map over `history` to extract position keys. No replay needed.

`GameReplay.scala` was deleted — its only purpose was replaying moves for undo, which is no longer necessary.

`GameSnapshot` lives in `chess.model` alongside `SessionState` because game history is a domain concept, not a UI concern. Both TUI and web GUI share it through the `SubscriptionRef[SessionState]`.

## Consequences

**Benefits:**
- Undo and redo are O(1) stack operations — no replay, no revalidation.
- Position history is directly available for threefold/fivefold repetition detection.
- The move list is derivable from history (`history.reverse.map(_._1)`), useful for PGN export and move log rendering.
- No `GameReplay` module to maintain.

**Trade-offs:**
- Memory is proportional to game length (one `GameState` per move). In practice, chess games are ~80 half-moves with a board of at most 32 pieces — negligible.
- `state` is derived from `history.head` rather than being a stored field, but the derivation is O(1).
