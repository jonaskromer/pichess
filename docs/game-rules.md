# Game Rules

## Move Notation

πChess accepts both **coordinate notation** and **Standard Algebraic Notation (SAN)**. See [notation.md](notation.md) for the full guide.

```
e2 e4   — coordinate: move the piece on e2 to e4
Nf3     — SAN: knight to f3
exd5    — SAN: pawn on e-file captures on d5
e8=Q    — SAN: pawn to e8, promote to queen
```

To quit the game, type `quit`. To flip the board, type `flip`. To see help, type `help`.

---

## Implemented Rules

### All pieces
- A piece cannot move to a square occupied by a friendly piece.
- It is illegal to move an opponent's piece.
- Turn order alternates: White moves first, then Black.

### Pawn
- Moves forward one square.
- May move forward two squares from its starting rank (rank 2 for White, rank 7 for Black).
- Captures diagonally one square forward.
- **En passant**: if a pawn advances two squares and lands beside an enemy pawn, the enemy pawn may capture it by moving diagonally to the skipped square. This right expires after the very next move.
- **Promotion**: when a pawn reaches the back rank (rank 8 for White, rank 1 for Black), it must promote to a Queen, Rook, Bishop, or Knight. Append the promotion suffix to the move (e.g. `e8=Q`, `exd8=N`). Promotion is mandatory — the game rejects a pawn reaching the back rank without specifying a piece.

### Rook
- Moves any number of squares horizontally or vertically.
- Cannot jump over pieces.

### Bishop
- Moves any number of squares diagonally.
- Cannot jump over pieces.

### Queen
- Moves any number of squares horizontally, vertically, or diagonally.
- Cannot jump over pieces.

### Knight
- Moves in an L-shape: two squares in one direction, one square perpendicular.
- Can jump over other pieces.

### King
- Moves one square in any direction (horizontal, vertical, or diagonal).
- **Castling**: the king moves two squares toward a rook, and that rook jumps to the other side of the king. Requires:
  - Neither the king nor the chosen rook has previously moved (tracked via `CastlingRights`).
  - All squares between the king and rook are empty.
  - The king is not currently in check.
  - The king does not pass through or land on a square attacked by an enemy piece.
- **Kingside** (`O-O`): king from e1/e8 to g1/g8, rook from h1/h8 to f1/f8.
- **Queenside** (`O-O-O`): king from e1/e8 to c1/c8, rook from a1/a8 to d1/d8.
- Castling rights are permanently lost when the king moves, or when the relevant rook moves or is captured.

### Check
- A move that leaves the player's own king in check is rejected.
- The checked king is highlighted in both the TUI (red) and the web GUI.
- The `+` suffix is appended to SAN output when a move gives check.

### Checkmate
- After each move, the game checks whether the opponent has any legal move. If the opponent's king is in check and no legal move exists, the position is checkmate.
- The game status changes to `Checkmate(winner)` and no further moves are accepted.
- The `#` suffix is appended to SAN output when a move delivers checkmate (instead of `+`).

---

## Not Yet Implemented

| Rule | Status |
|---|---|
| Stalemate detection | Not implemented |
| Draw conditions (50-move rule, threefold repetition, insufficient material) | Not implemented |

Illegal moves are rejected with an error message and the player is prompted to retry.

> Implementation of the missing rules is tracked in [roadmap.md — Phase 2](roadmap.md#phase-2--missing-chess-rules).
