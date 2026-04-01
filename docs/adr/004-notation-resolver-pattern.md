# ADR 004 — Notation parsing via Strategy / Chain of Responsibility

## Status

Accepted (signature updated: `Option[Either[GameError, Move]]` became `IO[GameError, Option[Move]]` per [ADR 005](005-pure-domain-model-zio-at-boundaries.md); the Strategy/Chain-of-Responsibility pattern is unchanged)

## Context

πChess accepts multiple move notation styles (coordinate, SAN, castling, with more possible in the future). Initially, all parsing lived in a single `MoveParser` object using a chain of regex matches, and resolution (finding which piece can reach the destination) lived in a separate `SanResolver`. This had two problems:

1. **Mixed concerns** — `MoveParser` handled both regex matching and constructing intermediate `ParsedMove` values. `SanResolver` then converted these into `Move` by consulting the board state. The intermediate `ParsedMove` enum existed only to bridge the two, adding a type with no real domain meaning.
2. **Adding a notation style** required changes in multiple places: a new regex in `MoveParser`, a new `ParsedMove` variant, and a new case in `SanResolver`.

## Decision

Extract notation parsing into a `chess.notation` package. Each notation style is a `NotationResolver` — an object implementing a single method:

```scala
trait NotationResolver:
  def parse(input: String, state: GameState): Option[Either[GameError, Move]]
```

- Returns `None` if the input doesn't match this notation style.
- Returns `Some(Right(move))` on a successful match and resolution.
- Returns `Some(Left(error))` if the input matches but is invalid (e.g. ambiguous SAN, unimplemented castling).

`MoveParser` becomes a thin orchestrator that chains the resolvers and returns the first match (Chain of Responsibility). The `ParsedMove` intermediate type is eliminated — resolvers produce `Move` directly.

A `SanSerializer` was added in the same package, providing the inverse: `toSan(move, state): String`.

### Why `Option[Either[...]]` instead of just `Either`?

A resolver needs to express three outcomes: "not my notation" (`None`), "my notation but invalid" (`Some(Left)`), and "success" (`Some(Right)`). Using `Either` alone would force every resolver to attempt parsing every input and produce error messages for notation styles they don't handle, which muddies error reporting.

### Why a separate `chess.notation` package?

Resolvers and the serializer are about notation — representing chess moves as text. They are not controllers (they don't handle user interaction) and not domain model (they depend on the model but are not part of it). A dedicated package makes the dependency direction explicit: `controller → notation → model`.

## Consequences

**Benefits:**
- Adding a new notation style means writing one object that implements `NotationResolver` and registering it in `MoveParser.resolvers`. No other files change.
- Each resolver is self-contained: regex, parsing, and board-based resolution in one place.
- The `ParsedMove` intermediate type is eliminated, reducing the number of concepts a reader must understand.
- `SanSerializer` lives alongside the resolvers, keeping notation concerns co-located.

**Trade-offs:**
- Resolver order in the chain matters. Coordinate notation (`e2e4`) is checked before SAN (`e4`) because coordinate patterns are more specific. Reordering could cause mis-parses.
- Each resolver receives `GameState` even if it doesn't need it (e.g. `CoordinateResolver` ignores the state). This is a minor cost for interface uniformity.
