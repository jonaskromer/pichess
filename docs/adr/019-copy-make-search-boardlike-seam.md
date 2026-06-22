# ADR 019 — Copy-make search via a read-only BoardLike/PositionView seam

## Status

Accepted

## Context

The engine searches millions of nodes; the immutable domain
(`GameState`/`BoardState`) re-materialises a fresh board per node. Profiling put
the per-node immutable `BoardState` at ~47 % of depth-6 hybrid allocation
(`bot-engine/.../internal/MutableBoard.scala:11-12`), and the whole immutable
per-move state (BoardState + GameState + apply-`Some`) at ~77 %
(`docs/elo-roadmap.md:75-76`). But move generation, NNUE/HCE eval, Zobrist
hashing and check detection are all **reads** — they don't need immutability,
they need a fast reader.

## Decision

Split the *representation* from the *reader interface*.

- **One reader set:** traits `PositionView`
  (`activeColor`/`enPassantTarget`/`castlingRights`/…, `PositionView.scala:14-19`)
  and `BoardLike` (12 piece bitboards + `get`/occupancy, `BoardLike.scala:18-37`).
- The **immutable** domain implements them: `GameState extends PositionView`
  (`:5`), `BoardState extends BoardLike` (`:20`).
- The **search** uses a mutable, **pre-allocated per-ply** position that *also*
  implements them: `SearchPos extends PositionView` /
  `MutableBoard extends BoardLike` (`SearchPos.scala:23`, `MutableBoard.scala:23`),
  held in `Array.fill(maxPly + 2)(new SearchPos)` (`AlphaBetaSearch.scala:48`).
  `copyMakeInto(child, moveInt)` (`SearchPos.scala:51`) copies the parent into the
  next-ply buffer then mutates the copy — **copy-make**, not make/unmake (there is
  no unmake).
- Because both backings are `PositionView`/`BoardLike`, **one** reader set serves
  both: `BitboardMoveGen.fillCapturesAndQuiets(state: PositionView)`,
  `NnueEvaluator.evaluate(PositionView)`, the HCE `evaluate(PositionView)`,
  `Zobrist.hash(PositionView)`, `MoveValidator.isInCheck(BoardLike, Color)` (the
  last two live in the `rules` module but read the same traits).

## Consequences

**Benefits:**
- Measured win: hybrid depth-6 allocation 14.7 → 7.0 MB/op (−52 %) and ~6 % faster
  (`docs/elo-roadmap.md:77-78`), with **no** duplicate movegen/eval — one reader
  set, two backings.
- Proven equivalent: `PerftSpec` re-pointed at copy-make yields identical perft
  counts (`:88-89`).

**Trade-offs:**
- `MutableBoard`/`SearchPos` are genuinely mutable and `private[engine]` — they
  trade the safety of immutability for speed, contained behind the trait.
- Two board representations to keep in lockstep behind one interface; perft is the
  guard that they stay equivalent.

Alternatives rejected: **make/unmake** (mutate-in-place + undo — fiddlier undo
logic, no copy-elision win here); a **search-only duplicate movegen** (a second
code path to keep correct); **immutable-everywhere** (the discarded ~77 %
allocation path, removed along with the dead `applyMoveInt`).
