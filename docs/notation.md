# Move Notation Guide

πChess accepts two notation styles: **coordinate notation** and **Standard Algebraic Notation (SAN)**. Both can be used interchangeably.

---

## Coordinate Notation

Enter the source square followed by the destination square. The separator is flexible.

| Input | Meaning |
|---|---|
| `e2 e4` | Move the piece on e2 to e4 (space) |
| `e2e4` | Same move, no separator |
| `e2-e4` | Same move, dash separator |

Squares are written as **column** (`a`–`h`) then **row** (`1`–`8`). White starts on rows 1–2; Black on rows 7–8.

---

## Standard Algebraic Notation (SAN)

The official notation used in tournament chess. No source square is needed — the game finds the piece automatically.

### Pawn moves

| Input | Meaning |
|---|---|
| `e4` | Push a pawn to e4 |
| `d5` | Push a pawn to d5 |
| `exd5` | Pawn on the e-file captures on d5 |
| `cxb4` | Pawn on the c-file captures on b4 |

### Piece moves

Piece letters are always **uppercase**: `N` Knight · `B` Bishop · `R` Rook · `Q` Queen · `K` King

| Input | Meaning |
|---|---|
| `Nf3` | Knight to f3 |
| `Bc4` | Bishop to c4 |
| `Rd1` | Rook to d1 |
| `Qd8` | Queen to d8 |
| `Ke2` | King to e2 |
| `Nxf3` | Knight captures on f3 |
| `Bxc6` | Bishop captures on c6 |

### Disambiguation

When two pieces of the same type can both reach the destination, add the source **file**, **rank**, or both to distinguish them.

| Input | Meaning |
|---|---|
| `Nbd2` | The knight on the b-file moves to d2 |
| `N1f3` | The knight on rank 1 moves to f3 |
| `Rd2f2` | The rook on d2 moves to f2 |
| `Raxd5` | The rook on the a-file captures d5 |

### Check and checkmate suffixes

`+` (check) and `#` (checkmate) are accepted after any move and ignored — the game does not yet detect check or checkmate.

| Input | Treated as |
|---|---|
| `Nf3+` | `Nf3` |
| `Qxf7#` | `Qxf7` |

---

## Not Yet Supported

| Notation | Reason |
|---|---|
| `O-O` / `O-O-O` | Castling is not yet implemented |
| `e8=Q` | Pawn promotion is not yet implemented |

---

## Board Coordinates

```
  a  b  c  d  e  f  g  h
8 ♜  ♞  ♝  ♛  ♚  ♝  ♞  ♜  8   ← Black back rank
7 ♟  ♟  ♟  ♟  ♟  ♟  ♟  ♟  7
6                            6
5                            5
4                            4
3                            3
2 ♙  ♙  ♙  ♙  ♙  ♙  ♙  ♙  2
1 ♖  ♘  ♗  ♕  ♔  ♗  ♘  ♖  1   ← White back rank
  a  b  c  d  e  f  g  h
```
