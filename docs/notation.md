# Move Notation Guide

πChess accepts two notation styles: **coordinate notation** and **Standard Algebraic Notation (SAN)**. Both can be used interchangeably.

Internally, notation parsing is handled by the `chess.notation` package, which provides a resolver for each style. The controller layer (`MoveParser`) chains the resolvers and returns the first match.

---

## Coordinate Notation

Enter the source square followed by the destination square. The separator is flexible.

| Input | Meaning |
|---|---|
| `e2 e4` | Move the piece on e2 to e4 (space) |
| `e2e4` | Same move, no separator |
| `e2-e4` | Same move, dash separator |
| `e7 e8=Q` | Move pawn to e8 and promote to queen |

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

### Pawn promotion

When a pawn reaches the back rank (rank 8 for White, rank 1 for Black), it must promote. Append `=` followed by the piece letter (`Q`, `R`, `B`, or `N`).

| Input | Meaning |
|---|---|
| `e8=Q` | Push pawn to e8, promote to queen |
| `e8=N` | Push pawn to e8, promote to knight (underpromotion) |
| `exd8=R` | Pawn on e-file captures on d8, promote to rook |

Promotion is mandatory — the game will reject a pawn move to the back rank without a promotion suffix.

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

`+` (check) and `#` (checkmate) suffixes are accepted in input and also appended automatically to SAN output: `+` when a move gives check, `#` when a move delivers checkmate.

| Input | Meaning |
|---|---|
| `Nf3+` | Knight to f3 (check suffix accepted) |
| `Qxf7#` | Queen captures on f7 (checkmate suffix accepted) |

---

## Move Log

After each move, the game displays the last two moves in SAN with color-coded labels:

```
White: e4 -> Black: e5
```

The `White` and `Black` labels are styled with ANSI colors matching their side (black-on-white and white-on-black respectively).

---

### Castling

| Input | Meaning |
|---|---|
| `O-O` | Kingside castling (king to g-file, rook to f-file) |
| `O-O-O` | Queenside castling (king to c-file, rook to d-file) |

Castling requires that neither the king nor the chosen rook has moved, all squares between them are empty, the king is not in check, and the king does not pass through or land on an attacked square.

---

## FEN (Forsyth-Edwards Notation)

FEN encodes a complete chess position as a single line of text:

```
load rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1
```

A FEN string has six space-separated fields:

| Field | Example | Meaning |
|---|---|---|
| Placement | `rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR` | Piece positions, rank 8 to rank 1, separated by `/`. Letters are pieces (`KQRBNPkqrbnp`), digits are consecutive empty squares. Uppercase = White, lowercase = Black. |
| Active color | `b` | Whose turn: `w` (White) or `b` (Black) |
| Castling | `KQkq` | Available castling rights (`K`/`Q` = White king/queenside, `k`/`q` = Black), or `-` for none |
| En passant | `e3` | Target square for en passant capture, or `-` |
| Halfmove clock | `0` | Moves since last pawn push or capture (for the 50-move rule) |
| Fullmove number | `1` | Incremented after Black moves, starts at 1 |

Common starting positions:

| FEN | Description |
|---|---|
| `rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1` | Standard initial position |
| `4k3/8/8/8/8/8/8/4K3 w - - 0 1` | Kings only (endgame testing) |
| `r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1` | Castling test position |

---

## PGN (Portable Game Notation)

PGN records a full game as a sequence of SAN moves with metadata headers.

### Import / Export

Use the unified `load` and `export` commands:

```
load 1. e4 e5 2. Nf3 Nc6 *
export pgn
```

The `load` command auto-detects the format (FEN, PGN, or JSON). The `export` command requires a format argument: `export fen`, `export pgn`, or `export json`.

Full PGN with headers is also accepted:

```
load [Event "Test"] [Result "*"] 1. e4 e5 2. Nf3 Nc6 *
```

A `[FEN "..."]` header sets a custom starting position for the game.

### PGN Movetext Rules

- Move numbers (`1.`, `2.`, etc.) are parsed and stripped
- Result tokens (`1-0`, `0-1`, `1/2-1/2`, `*`) mark the game result
- Comments in braces (`{this is a comment}`) are ignored
- Numeric Annotation Glyphs (`$1`, `$2`, etc.) are ignored
- Each SAN move is replayed through the game engine and validated

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
