# Addendum: SA-02 — Functional Style

> Source: `docs/slides/SA-02-Functional Style.pdf`

---

## ScalaChess Domain Abstractions

These types form the chess domain model for this project:

`Square` · `File` · `Rank` · `Side` · `Color` · `Role` · `Piece` · `Board` · `Bitboard` · `History` · `Status` · `Position` · `Move`

**Bitboard**: represents 64 board squares. **Implement WITHOUT bit operations** — use standard Scala collections and logic instead.

---

## Functional Style Mandates

| Rule | Detail |
|------|--------|
| `val` over `var` | Never mutate local state |
| `case class .copy()` | All "mutations" produce new instances |
| Functions as objects | Pass, return, store functions |
| Expressions over statements | Every construct has a value |
| Recursion over loops | No `for`/`while` imperative loops |
| No `null` | Use `Option`, `Either`, `Try` |
| No getters/setters | Case class fields are direct |
| Standard collections | `map`, `flatMap`, `filter`, `foldLeft`, `foldRight` |

---

## Rail Metaphor for Functions

A function is a **transformative tunnel** — one typed input in, one typed output out.

Every `if` branches like a railway switch. Cascading null-checks create exponential track complexity. **Avoid this.**

**Composition**: if output type of f1 matches input type of f2, they compose into a single new function.

---

## Two-Track Pattern (Monads)

Functions that can fail have **two output tracks**: success (green) and failure (red).

Rules:
- Once on the **red track**, you stay there
- From the **green track**, you can move to red at any point
- Chain operations cleanly — no nested conditionals

This is what `Option`, `Try`, `Either`, and `Future` implement.

---

## The Four Monads

| Monad | Success | Failure |
|-------|---------|---------|
| `Option[T]` | `Some(x)` | `None` |
| `Try[T]` | `Success(x)` | `Failure(e)` |
| `Either[E,T]` | `Right(x)` | `Left(e)` |
| `Future[T]` | `Success(x)` | `NotCompleted` / `Failure(e)` |

A Monad is a **container** (crate/box) for a value with an algebraic structure (Monoid). Take value out → transform → put in new container.

---

## For-Comprehension

Syntactic sugar for chaining Monad operations:

```scala
// Single generator → expands to map
for (x <- e1) yield f(x)
// compiles to: e1.map(x => f(x))

// Multiple generators → expands to flatMap + filter + map
for {
  i <- 1 to n
  j <- 1 to i
  if isEven(i + j)
} yield (i, j)
// compiles to: (1 to n).flatMap(i => (1 to i).filter(j => isEven(i+j)).map(j => (i,j)))
```

Use for-comprehension to unpack Monads without explicit pattern matching.

---

## Task 1 (Functional Domain)

Implement the chess domain abstractions in pure functional Scala:
- All types as `case class` or `sealed trait`
- No mutation (`val` everywhere)
- Bitboard without bit operations
- ScalaTest: 100% coverage

## Task 2 (Monads)

- Replace all `null` / `Null` with `Option`
- Replace `try-catch` with `Try` Monad
- Use for-comprehension to unpack Monads
- Apply two-track pattern throughout the codebase
