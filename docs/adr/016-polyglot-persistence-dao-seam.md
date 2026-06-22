# ADR 016 — Polyglot persistence behind a DAO seam, env-selected, proven interchangeable

## Status

Accepted

## Context

The platform persists two aggregates — game state and lobbies — and the lecture
track covers several storage technologies (Slick/Postgres SA-07, MongoDB SA-08,
plus Redis and Cassandra). We wanted to exercise all of them *without* rewriting
service code per backend, and to choose a production stack **empirically** rather
than assert one.

The risk of "support five databases" is a leaky abstraction: a driver type
(`DBIO`, `Future`, `BsonDocument`) escapes into callers, or the backends quietly
diverge in behaviour.

## Decision

One **DAO trait per aggregate** — `LobbyRepository` and `GameRepository`
(`persistence/api/.../LobbyRepository.scala:19`, `GameRepository.scala:19`) —
exposing only `IO[<Error>, A]`; the trait doc forbids driver types from leaking
through.

- **Five interchangeable backends per aggregate.** InMemory (in the `api` module)
  plus Postgres / Mongo / Redis / Cassandra (one sbt module each) — ten impl
  files, e.g. `MongoLobbyRepository.scala`, `CassandraGameRepository.scala`.
- **Runtime selection by env var.** `BackendConfig` reads `PICHESS_BACKEND` and
  `PICHESS_CACHE` (`BackendConfig.scala:39-40`); absent ⇒ `Backend.Mongo` (`:57`)
  + `CacheBackend.Redis` (`:75`).
- **Two stacked decorators, each itself a repository.**
  `CachedLobbyRepository`/`CachedGameRepository` (`persistence/cache/...`) is a
  read-through Redis cache taking a `(cache, primary)` pair of repositories — not
  a raw client; `TracedLobbyRepository`/`TracedGameRepository`
  (`persistence/runtime/...`) wrap each call in a span. `PersistenceLayers`
  composes them innermost→outermost as **primary → (optional) cache → tracing**
  (`PersistenceLayers.scala:60-63`).
- **One shared contract test per aggregate.**
  `LobbyRepositoryContract`/`GameRepositoryContract` (`persistence/contract/...`)
  is an abstract `ZIOSpecDefault`; each container-backed backend subclasses it
  against a Testcontainers layer (e.g.
  `PostgresLobbyRepositoryContract extends LobbyRepositoryContract`), holding all
  four observationally equal. InMemory keeps its own standalone specs.

Alternative rejected: **pick one database** and bind the services to it — simpler
(no matrix), but it forecloses the empirical bake-off and the lecture coverage
for a saving we judged smaller than the optionality. The *result* of the
bake-off (a stress matrix selecting Mongo+Redis as the shipping default) is
written up in `docs/db-selection-report.md`; **this ADR records the decision to
build the seam, that report records its output.**

## Consequences

**Benefits:**
- Swapping storage is a single env var, zero service-code change — and the one
  shared contract per aggregate means a swap cannot silently regress semantics.
- The decorator stack (cache, tracing) is orthogonal to the backend: written
  once, it applies to all five.
- The seam is what made the empirical backend selection possible at all.

**Trade-offs:**
- Five backends plus a Testcontainers matrix to keep green — real maintenance
  surface for four DBs we don't ship in prod.
- InMemory sits outside the shared contract (its own specs), so it isn't held to
  the exact same suite as the container backends.
