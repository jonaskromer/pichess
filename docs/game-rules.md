# Game Rules

## Move Notation

Moves are entered as two space-separated squares: `<from> <to>`

```
e2 e4   — move the piece on e2 to e4
g1 f3   — move the piece on g1 to f3
```

- Columns: `a` through `h` (left to right from White's perspective)
- Rows: `1` through `8` (White starts on rows 1–2, Black on rows 7–8)

To quit the game, type `quit`.

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

---

## Not Yet Implemented

| Rule | Status |
|---|---|
| Castling (kingside and queenside) | Not implemented |
| Pawn promotion | Not implemented |
| Check detection | Not implemented |
| Checkmate / stalemate detection | Not implemented |
| Draw conditions (50-move rule, threefold repetition, insufficient material) | Not implemented |

Illegal moves are rejected with an error message and the player is prompted to retry. The game does not currently end automatically — it runs until the user types `quit`.

> Implementation of the missing rules is tracked in [roadmap.md — Phase 2](roadmap.md#phase-2--missing-chess-rules).
