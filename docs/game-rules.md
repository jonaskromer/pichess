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

---

## Not Yet Implemented

| Rule | Status |
|---|---|
| Castling (kingside and queenside) | Not implemented |
| Check detection | Not implemented |
| Checkmate / stalemate detection | Not implemented |
| Draw conditions (50-move rule, threefold repetition, insufficient material) | Not implemented |

Illegal moves are rejected with an error message and the player is prompted to retry. The game does not currently end automatically — it runs until the user types `quit`.

> Implementation of the missing rules is tracked in [roadmap.md — Phase 2](roadmap.md#phase-2--missing-chess-rules).
