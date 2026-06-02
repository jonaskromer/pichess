# Performance experiments — a step-by-step tutorial

> Audience: a dev or reviewer who needs to understand how persistence
> and performance work in this repo, then run the two experiments
> themselves. Also doubles as the entry point to the generated report.
>
> **Per-run results live in
> `perf-reports/<TS>/performance-test-results.md`** — built by `make
> perf-report` after each run. This doc is the static guide; the
> generated file is the data.

This guide is built around two experiments:

1. **The persistence experiment** — drive the perf suite across every
   backend × cache × workload combination and rank the configs.
2. **The performance experiment** — find hot paths via profiling, ship
   optimised and naive implementations side-by-side gated by DI, then
   compare them on the suite.

Read top-to-bottom for the full picture or jump to the section you need:

- [Architecture in one page](#architecture-in-one-page)
- [The persistence layer](#the-persistence-layer)
- [The performance stack](#the-performance-stack)
- [Experiment 1 — DB matrix](#experiment-1--db-matrix)
- [Experiment 2 — Optimisations](#experiment-2--optimisations)
- [Day-to-day: Grafana, Jaeger, async-profiler](#day-to-day-grafana-jaeger-async-profiler)
- [Extending the experiments](#extending-the-experiments)
- [The generated report](#the-generated-report)
- [Reproducing published results](#reproducing-published-results)

---

## Architecture in one page

Seven services run in Docker. **State** lives in the persistence
backend that game-service + repository + lobby-service all read from
(picked at startup via `PICHESS_BACKEND`). **Events** flow through
Kafka when one of the projection profiles is active.

```
                      ┌─────────┐
                      │ web-ui  │  (Scala.js SPA served by gateway)
                      └────┬────┘
                           │  HTTP + SSE
                           ▼
   ┌──────┐     gRPC    ┌─────────┐  events   ┌───────┐  consume
   │ TUI  │────────────▶│ gateway │  ───────▶ │ kafka │ ──────────┐
   └──────┘             └─┬─────┬─┘           └───────┘            ▼
                          │     │                          ┌──────────┐
                  gRPC ───┘     └─ HTTP                    │ opening  │ ─▶ Neo4j
                  ▼                ▼                       └──────────┘
            ┌──────────────┐ ┌──────────────┐              ┌──────────┐
            │ game-service │ │ lobby-service│              │analytics │ ─▶ ClickHouse
            └──────┬───────┘ └──────┬───────┘              └──────────┘
                   │ persistence    │ persistence
                   ▼                ▼
            ┌────────────────────────────────┐
            │ chess.persistence.runtime      │
            │ → PersistenceLayers.gameRepo   │  (postgres / mongo /
            │ → PersistenceLayers.lobbyRepo  │   redis / cassandra /
            │ + optional CachedRepository    │   inmemory + cache decorator)
            └────────────────────────────────┘
```

For deeper coverage:
- Service roles + ports: [`docs/development.md` § Docker](development.md#docker)
- Module dependency map: [`docs/architecture.md`](architecture.md)
- ADRs (why each split happened): [`docs/adr/`](adr/)

---

## The persistence layer

### Module layout

```
persistence/
├── api/           — GameRepository / LobbyRepository traits, Backend
│                    enum, BackendConfig (reads PICHESS_BACKEND + _CACHE)
├── contract/      — shared TCK that every backend's spec implements
│                    so every impl is observably the same
├── cache/         — CachedGameRepository / CachedLobbyRepository
│                    decorators (Redis-backed, composable on top of
│                    any primary)
├── postgres/      — PostgresGameRepository / PostgresLobbyRepository +
│                    PostgresDatabase (HikariCP + Quill)
├── mongo/         — MongoGameRepository / MongoLobbyRepository +
│                    MongoClientLayer
├── redis/         — RedisGameRepository / RedisLobbyRepository +
│                    RedisLayers
├── cassandra/     — CassandraGameRepository / CassandraLobbyRepository +
│                    CassandraSession
└── runtime/       — PersistenceLayers — the single switch every service
                     Main uses to pick repos based on a BackendConfig.
```

Three services consume `PersistenceLayers`: game-service (game repo),
repository (game repo, write-side from Kafka events), and lobby-service
(lobby repo). They all share the same selection mechanism. Adding a
backend is a one-file change in `runtime/PersistenceLayers.scala` plus
a new module — see [Extending §
backend](#adding-a-new-backend).

### The five backends

| Backend | When it fits | When to avoid | Profile |
|---|---|---|---|
| `inmemory` | The baseline. Pure JVM map, no I/O. Use as the upper-bound benchmark and to isolate non-DB overhead in profiling. | Anywhere you need durability across restarts. | none (default) |
| `postgres` | Strong consistency + relational queries. Default choice when the access pattern includes lookups beyond "by id". | Latency-bound key-only reads (Redis wins) or write-heavy time series (Cassandra wins). | `postgres` |
| `mongo` | Schema agility — game/lobby snapshots are document-shaped and don't need joins. Reasonable middle ground. | High-cardinality secondary indexes or strict transactional invariants. | `mongo` |
| `redis` | Latency-bound key-value workloads. Single-digit-ms p99 under load. Good for hot-path caches; sometimes good enough as a primary. | Durability guarantees, complex queries, large objects. | `redis` |
| `cassandra` | Write-heavy, eventually-consistent, time-series-shaped event logs. Linear write scaling under partition. | Read-mostly small datasets — the LSM overhead doesn't pay back. | `cassandra` |

### The cache decorator

`PICHESS_CACHE=redis` wraps any primary repository in
`CachedGameRepository` (look-aside cache: read-through, write-through
invalidation). It's a decorator pattern, orthogonal to backend choice —
e.g. `PICHESS_BACKEND=postgres PICHESS_CACHE=redis` gives you postgres
durability with redis read latency for hot keys.

When `PICHESS_BACKEND=redis`, caching with redis is a no-op and is
skipped automatically (see `persistence/runtime/PersistenceLayers.scala`).

### How selection works at runtime

```scala
// At each service Main's startup:
for
  cfg <- BackendConfig.fromEnv       // reads PICHESS_BACKEND, PICHESS_CACHE
  _   <- serve(port, cfg)
yield ()

// Inside the service:
program.provide(
  PersistenceLayers.gameRepository(cfg),   // ← one method call
  ...
)
```

The user-facing surface for swapping backends is **env vars passed at
container start time**. Run-time switching isn't supported — backends
are picked once at startup and live for the lifetime of the JVM.

### Verifying which backend is active

```bash
curl http://localhost:8090/api/stack-info
# → {"backend":"postgres","extras":["redis","obs"]}
```

The endpoint surfaces what the gateway sees; if your stack came up
with different env (e.g. `PICHESS_CACHE` wasn't propagated), this is
where you'd notice.

---

## The performance stack

Seven layers, each addressing a different question. The same layout as
[`docs/performance.md`](performance.md) but trimmed to "what does each
do and why is it here".

| # | Layer | Question it answers | Tool |
|---|---|---|---|
| 1 | Load tests | "How does the service behave under N concurrent users?" | Gatling |
| 1b | Cross-stack load | "What can Gatling not see — browser, Kafka, gRPC?" | k6 (+ xk6-kafka, k6/browser) |
| 2 | Microbenchmarks | "Which pure function is the hot path?" | JMH |
| 3 | ZIO profiling | "Which effect is taking the time?" | zio-profiling (sampling, fiber-aware) |
| 4 | JVM profiling | "What is the JVM doing at the JIT-compiled-frame level?" | async-profiler |
| 5 | Metrics | "What's the live state of the running system?" | zio-metrics-connectors + Prometheus + Grafana |
| 6 | Tracing | "Where in the request fan-out is the slow span?" | zio-opentelemetry + Jaeger |

The two experiments use these layers as follows:

- **Persistence experiment**: Layer 1 generates the load, Layer 5 measures
  resource cost per backend. Layers 1b/2 are *backend-agnostic* (they
  isolate things that aren't affected by the DB choice) and don't run
  in the matrix.
- **Performance experiment**: Layers 3+4 surface the optimisation
  targets; Layers 1, 1b, 2, 5 measure the before/after delta. Layer 6
  helps interpret findings ("the slow span is in the gateway-to-game
  call, not in the DB").

For each layer's setup, runners, and output paths, the deep reference
is [`docs/performance.md`](performance.md).

---

## Experiment 1 — DB matrix

### Hypothesis

> Different persistence backends suit different workloads. There is no
> single "best" backend; the right answer depends on access patterns,
> concurrency, and tolerance for tail latency. A read-heavy hot-key
> workload favours Redis; a write-heavy event-log workload favours
> Cassandra; a balanced OLTP workload favours Postgres. Caching is a
> separate axis — Redis-as-cache atop a durable primary often beats
> either alone.

The matrix tests this hypothesis by running the same Gatling workload
against every (backend, cache) combination.

### Methodology

For each tuple in `BACKENDS × WORKLOADS × eligible-caches`:

1. **Rotate the stack** to `PICHESS_BACKEND=<backend>` and
   `PICHESS_CACHE=<cache>`, with the `obs` profile up so Prometheus is
   scraping and Jaeger is collecting traces (`TRACING_ENABLED=true`).
2. **Wait for readiness** — `/api/stack-info` is the gateway probe;
   `/healthcheck` on lobby is a backup.
3. **Warm up** with N (default 20) full game replays so the JVMs JIT
   the hot paths before measurement.
4. **Snapshot Prometheus** (baseline), **run the Gatling simulation**,
   **snapshot Prometheus** (final).
5. **Extract** Gatling's per-request statistics from `stats.js` into a
   per-tuple `summary.txt`.

When all tuples finish, `matrix.md` and `matrix-summary.csv` are
written, ranking configs per workload by p95 latency.

The default tuples (with `WORKLOADS=Game,Stress`):

| Backend | Caches tested | Workloads |
|---|---|---|
| inmemory | none | Game, Stress |
| postgres | none, redis | Game, Stress |
| mongo | none, redis | Game, Stress |
| redis | none | Game, Stress |
| cassandra | none, redis | Game, Stress |

= **16 runs**, ≈ 2-3 minutes each → matrix runtime ≈ 30-50 min total.

### Why these workloads, not others

| Workload | Why included | Why not (when excluded) |
|---|---|---|
| **Game** | Steady-state OLTP — represents typical "user plays a game" traffic. The baseline a backend must handle gracefully. | — always run |
| **Stress** | Saturation — drives the backend to its tail-latency cliff. Shows where queueing starts to dominate. | — always run |
| **Endurance** | Surfaces *slow* DB-side effects (Cassandra compaction, connection-pool exhaustion, index growth). | Doubles matrix runtime. Excluded by default; the right follow-up after Game+Stress narrows the field to 2-3 finalists. |
| **Spike** | Tests resilience to bursts, not raw throughput. | More relevant to the optimisation experiment than to backend selection. |
| **Volume** | Index growth + cold-cache behaviour. | Useful but redundant with Stress for most backends. |
| **Mixed** | Cross-service contention (lobby + game in parallel). | The DB matrix tests one repo at a time; cross-contention belongs in the optimisation experiment. |

### Running the matrix

```bash
# Pre-flight: all service images need to be built once.
make build

# Full matrix (Game + Stress, every backend, every eligible cache).
make db-matrix
```

The default is **lite mode**: app services (gateway / game-service /
repository / lobby-service) come up *once* at the start and stay warm
across rotations. Only the DB container and the three backend-
dependent services (game-service, repository, lobby-service) get
recreated when the backend or cache changes. The recreation is
sequenced — game-service first so its schema migration commits before
repository + lobby-service start theirs (otherwise the two concurrent
`CREATE TABLE IF NOT EXISTS games` would race inside Postgres's
`pg_type` catalog).

Compared to the old full-stack-down/up-per-rotation behaviour:
- ~half the peak memory (~3-4 GB vs ~6-8 GB)
- ~5-8 min off the full matrix runtime (warm JVMs need ~5s warmup vs 30s)
- Same data, same report

Tuning:

```bash
# Narrower set of backends:
BACKENDS=postgres,mongo make db-matrix

# Only Game (skip Stress) for a fast smoke:
WORKLOADS=Game make db-matrix

# Heavier stress run (longer holds, more peak users):
PEAK_USERS=200 HOLD_SECONDS=120 RATE_PER_SEC=10 make db-matrix

# Drop the obs profile entirely — saves another ~1 GB but loses the
# resource profile section of the report.
OBS=false make db-matrix

# Full down/up cycle per rotation (the original behaviour) — slower
# and heavier but with guaranteed clean state.
MATRIX_HEAVY=true make db-matrix
```

All Gatling system-property knobs propagate. Full list:
`BACKENDS`, `WORKLOADS`, `WARMUP_ITERS`, `PEAK_USERS`,
`RAMP_SECONDS`, `HOLD_SECONDS`, `RATE_PER_SEC`, `OBS`,
`MATRIX_HEAVY`.

### Reading the matrix output

Three artefacts land under `perf-reports/<TS>/matrix/`:

| File | What it tells you |
|---|---|
| `matrix.md` | Per-workload ranked tables (lowest p95 first). The "headline" view. |
| `matrix-summary.csv` | Raw per-config rows for ad-hoc analysis (drop into Pandas, jq, …). |
| `<backend>+<cache>/<workload>/` | Per-config artifacts: full Gatling report, Prometheus snapshots, sbt log. |

The generated report (next section) folds matrix + Prometheus + k6 + JMH
into `performance-test-results.md`. That's the file you read first.

### Interpreting the resource profile

Prometheus snapshots capture each service's heap, GC time, and CPU at
two moments (baseline = end of warmup, final = end of run). The
report's "Resource profile" table shows:

- **Heap** — `jvm_memory_used_bytes{area="heap"}` post-run total
  (proxy for steady-state peak in workloads where GC has settled).
- **GC time** — `jvm_gc_collection_seconds_sum` delta (cumulative
  seconds paused for GC during the run).
- **GC count** — `jvm_gc_collection_seconds_count` delta (number of
  pauses).
- **CPU s** — `process_cpu_seconds_total` delta.

What to look for:

| Pattern | What it suggests |
|---|---|
| One backend has noticeably higher heap → similar p95 | The backend's driver allocates more. Look at allocation profiles. |
| GC time grows faster than CPU time | Allocation pressure dominating; an optimisation that reduces allocation pays back. |
| p95 close, CPU very different | One backend is doing more work per request — look at trace fan-out in Jaeger. |
| postgres+redis ≪ postgres+none on read-heavy | The cache is doing its job. Validate `cache_hit_rate` if/when emitted. |

---

## Experiment 2 — Optimisations

### Hypothesis

> Profiling will surface several CPU-bound or allocation-bound hot
> paths. For each, a naive implementation is shippable but suboptimal;
> a more careful implementation reduces latency and/or allocation
> without changing observable behaviour. Both implementations can
> coexist behind a DI selector, so we can run the perf suite with each
> permutation and quantify the delta.

### The selector model

Every optimisation registers a per-component env var. Default value =
optimised (so production behaves correctly without setup). The `naive`
value flips the swap. A single override knob (`PICHESS_OPT_ALL=naive`)
sets every selector to its naive variant for the headline A/B.

```
  PICHESS_OPT_PG_POOL        = hikari    (default) | none         # ← Phase B headline
  PICHESS_OPT_FEN_PARSER     = fastparse (default) | combinator | regex
  PICHESS_OPT_LEGAL_MOVES    = memo      (default) | recompute
  PICHESS_OPT_POSITION_ALLOC = reuse     (default) | fresh        # Ray.walk allocation
  PICHESS_OPT_DTO_CACHE      = on        (default) | off
  PICHESS_OPT_SSE_MODE       = delta     (default) | full
  PICHESS_OPT_GRPC_POOL      = pool      (default) | percall
  PICHESS_OPT_ALL            = optimised (default) | naive
```

The selector is read once at startup and resolved to a ZIO `ZLayer`.
See [Extending §
optimisation](#adding-a-new-optimisation-selector) for the pattern.

### Optimisation list

Each optimisation pairs with a perf layer that's expected to surface
its impact most clearly. **#1 is the headline finding** from Phase B
profiling — see "Phase B findings" below for how it was identified.
The rest are a mix of profile-driven targets and known-quantity wins
from the perf-stack design.

| # | Optimisation | Layer | What changes | Expected signal |
|---|---|---|---|---|
| 1 | **Postgres connection pooling** (HikariCP) | 1 (Gatling, DB-backed) | The optimised path uses `slick.jdbc.HikariCPJdbcDataSource` so connections live in a reusable pool; the naive path keeps the current `Database.forURL` which calls `DriverDataSource.getConnection` → `org.postgresql.Driver.connect` → SCRAM/PBKDF2 per query | Game / Stress p95 ↓ ~25-40 % on postgres backend; SHA256 + Driver.connect frames disappear from `game-service` flame graph |
| 2 | FEN parser selector | 2 (JMH) | Three existing parser impls (`fastparse` / `combinator` / `regex`) wired to one selector. `fastparse` is the optimised default | µs/op delta in `FenParserBenchmark` |
| 3 | Legal-moves memoisation | 2 (JMH) | Cache `hasLegalMove(fen)` results; naive recomputes from scratch each call | µs/op delta in `MoveValidatorBenchmark` |
| 4 | `Position.apply` allocation reduction in `MoveValidator` / `Ray.walk` | 2 (JMH) + 1 (Gatling allocation rate) | Reuse `Position` instances on the legal-moves walk; naive allocates per ray step | Allocation samples for `Position.apply` ↓ in alloc profile; GC time ↓ in Prometheus |
| 5 | `BoardStateDto` serialisation cache | 1 (Gatling) | FEN-keyed cache for the JSON projection of a state; naive serialises on every request | Allocation rate ↓ in Prometheus, p95 ↓ marginally on read-heavy mixes |
| 6 | SSE delta mode | 1 (Gatling) | Push only the delta on `/api/games/<id>/events`; naive pushes the full snapshot per move | Bytes-out ↓, fiber memory ↓ |
| 7 | gRPC channel pool (gateway → game-service) | 1b (k6 grpc) + 1 (Gatling) | Single long-lived ManagedChannel reused across requests; naive opens a channel per call | p95 ↓ visibly in `make k6-grpc`; `Driver.connect`-style HTTP/2-handshake frames disappear from gateway profile |

Two of these (FEN parser, BoardStateDto cache) are partially built —
the FEN parsers exist as three classes, the state cache exists for
`Game` snapshots but not for the DTO. The rest are new work in Phase D.

### Phase B findings — how the headline #1 was found

`make profile-async-cpu` against `game-service` during a sustained
Stress run (`PEAK_USERS=80`, `HOLD_SECONDS=240`, postgres backend)
produced a flame graph dominated by **Postgres connection
establishment**.

Top stacks from the `itimer` (on-CPU) profile, by sample count:

| Samples | Path |
|---:|---|
| 659 + 115 + 62 + 55 + 35 (≈980, ~25 % of on-CPU samples) | `slick.AsyncExecutor.run` → `slick.JdbcBackend.acquireSession` → `slick.JdbcBackend.createSession` → `DataSourceJdbcDataSource.createConnection` → `**DriverDataSource.getConnection**` → `org.postgresql.Driver.connect` → `ConnectionFactoryImpl.openConnection` → `ScramAuthenticator.handleAuthenticationSASLContinue` → PBKDF2 / SHA-256 |
| 337 + 263 | SHA-256 compression intrinsic — invoked from the SCRAM hash above |
| 107 + 75 + 67 + 62 + 42 | G1 concurrent marking — *follow-on effect* of the allocation rate the connection thrash is creating |

The smoking gun is the `DriverDataSource.getConnection` →
`Driver.connect` chain. **A pooled DataSource never calls `Driver.connect`
once the pool is warm**; the fact that we see it ~1000 times in a
30-second window means a fresh socket + SCRAM handshake is happening
for every query.

Code citation:
`persistence/postgres/.../PostgresDatabase.scala:62-81` — the
docstring claims "HikariCP-backed Slick Database" but the
implementation is `Database.forURL(url, user, password, driver,
executor = AsyncExecutor(...))`. The `executor` parameter only controls
the *query scheduling* thread pool; per the Slick docs
(`numThreads` "has no effect on the number of connections in the
connection pool"), there is no connection pool here — the
`DriverDataSource` opens a fresh JDBC connection on every
`getConnection` call.

The fix is `Database.forDataSource(new com.zaxxer.hikari.HikariDataSource(cfg))`
with HikariCP configured for `maximumPoolSize = settings.maxConnections`.
The selector wraps both paths so the regression is reproducible at
will via `PICHESS_OPT_PG_POOL=none make perf BACKENDS=postgres MODE=Stress`.

Secondary findings (lower priority, kept for documentation):

- **`MoveValidator.isInCheck → Ray.walk → Position.apply`** shows up in
  the allocation profile (≈33 samples). Each ray step allocates a fresh
  `Position`; reusing instances trims allocation rate without changing
  the algorithm. Folded in as optimisation #4.
- **gRPC tracing is not joined gateway↔game-service** — the gateway-side
  span shows ~10 ms for a `/move` POST, but we can't see how much of
  that is the gRPC fan-out vs game-service work. Adding the gRPC
  interceptor is in [`docs/performance.md`'s Deferred Work table](performance.md#deferred-work),
  not in the optimisation list — it's an instrumentation fix, not an
  optimisation.
- **`PrometheusEncoder.unsafeEncode` calls `String.replaceAll` per
  metric on every scrape.** Visible in the profile but ~1 sample —
  not worth optimising at this load.

Raw profile artifacts (regenerate with `make profile-async-cpu SERVICE=game-service`):

| File | Contents |
|---|---|
| `perf-reports/profiles/async-cpu-game-service-<ts>.html` | Interactive flame graph, openable in any browser |
| `perf-reports/profiles/itimer-game-active.txt` | Collapsed text — top stacks by `awk '{print $NF, $0}' \| sort -rn` |
| `perf-reports/profiles/alloc-game-active.txt` | Same shape but for allocation events |

### Running the experiment

```bash
# Default: everything optimised.
make perf-all

# Headline regression run: every selector flipped to naive.
PICHESS_OPT_ALL=naive make perf-all

# A/B one optimisation at a time, e.g. legal-moves:
PICHESS_OPT_LEGAL_MOVES=recompute make perf-all
```

The selectors are read by the relevant service Mains, so flipping any
of them is **a stack restart away** — no rebuild required. `make
perf-all` reruns the full Gatling cross-backend + all three k6
surfaces + the JMH bench, which is the canonical evidence for each
swap.

### Reading the optimisation output

The generated report (`performance-test-results.md`) has an
"Optimisation A/B" section that compares the two extremes. For per-
optimisation breakdown, look at:

- **Layer 2 deltas**: `bench-<scope>.json` rows for the affected
  benchmark class — direct µs/op comparison. See "Module benches"
  below for which scope to target.
- **Layer 1 deltas**: the `comparison.md` p95 column per backend.
- **Layer 1b deltas**: k6 per-surface `summary.json` p95.
- **Resource profile deltas**: the Prometheus table — allocation /
  GC / CPU changes that the latency tables miss.

---

## Module benches — isolated per-subsystem perf

The JMH bench suite is partitioned so you can A/B a single subsystem
without running the whole application or the whole bench suite. Each
subset runs in seconds when scoped narrowly.

| Target | Tests | Needs |
|---|---|---|
| `make bench-codec` | FEN / SAN / PGN parsers + serialisers + Zobrist hash | Pure JVM — no Docker, no stack |
| `make bench-rules` | `MoveValidator`, `Ray.walk`, `Game.applyMove` | Pure JVM |
| `make bench-persistence` | Per-backend repo throughput (Phase D — testcontainer per backend) | Docker, one DB container at a time |
| `make bench-wire` | `BoardStateDto` JSON, `GameDomainEvent` JSON, protobuf `StateReply` (Phase D) | Pure JVM |
| `make bench` | Union of the above — same as the legacy full-suite target | Whatever the union needs |

**Iteration control** is via env:

```bash
make bench-codec                       # default: 3 warmup + 3 measurement × 1 fork
BENCH_QUICK=true    make bench-codec   # 1+1 — fast feedback, no confidence interval
BENCH_THOROUGH=true make bench-codec   # 5+10 × 2 forks — publication-grade
```

`BENCH_QUICK` is what you reach for during a tight develop-measure-tweak
loop. The numbers are noisy (no confidence interval — JMH writes
`scoreError = "NaN"` with only 1 sample, surfaced as `n/a` in the
report's Error column) but the ratio between optimised and naive is
usually still informative. Use `BENCH_THOROUGH` for the final numbers
that go into the report.

### Output, aggregation, the perf-report scope tables

Every subset writes a JSON file named `perf-reports/bench-<scope>-<ts>.json`,
e.g. `bench-codec-20260603T020012Z.json`. The report generator
(`make perf-report`) picks these up and emits one sub-table per scope
under `## JMH microbenchmarks`, so the final report has separate
codec / rules / persistence / wire tables — easy to compare against
specific optimisations.

A bare `make bench` (the unioned form) writes `bench-<ts>.json` with
no scope label; the report shows that under `### Scope: all`.

### Per-optimisation bench pairing (Phase D convention)

Each Phase D optimisation ships with a module bench in the matching
scope so the A/B is reproducible at the smallest level:

| Optimisation # | Bench scope | Benchmark class |
|---|---|---|
| 1 — Postgres connection pool | `persistence` | `PostgresRepoBenchmark` (testcontainer-managed) |
| 2 — FEN parser selector | `codec` | extends `FenParserBenchmark` with a `@Param` over the impls |
| 3 — Legal-moves memo | `rules` | extends `MoveValidatorBenchmark` |
| 4 — `Position.apply` reuse | `rules` | extends `RayWalkBenchmark` |
| 5 — `BoardStateDto` cache | `wire` | `DtoSerialisationBenchmark` (Phase D) |
| 6 — SSE delta mode | — | no module bench — Layer 1 only |
| 7 — gRPC channel pool | — | no module bench — Layer 1b only |

Module benches with testcontainers (`bench-persistence`) trade ~5-10 s
of container startup for "clean room" data: a fresh DB per `@Trial`,
no leftover state, no other services on the host competing for CPU.
Peak resource: one DB container (~250-800 MB) + the JMH JVM
(~300 MB) — fits in well under a gigabyte for everything except
Cassandra.

---

## Day-to-day: Grafana, Jaeger, async-profiler

### Watching Grafana during a run

```bash
make stack-postgres EXTRA=obs   # brings up Prometheus + Grafana + Jaeger
make grafana                    # opens http://localhost:3000

# Now kick off a perf run from another shell:
make perf BACKENDS=postgres MODE=Stress OBS=true
```

The "piChess — JVM overview" dashboard ships with these panels:

| Panel | Look for |
|---|---|
| Heap (per service) | Smooth saw-tooth = steady-state GC. Monotonic climb = leak. |
| GC pause time | Spikes correlated with workload arrival; investigate if they exceed ~100 ms p99. |
| Active fibers | Should stay bounded under steady load. Climbing means back-pressure isn't propagating. |
| Thread count | Sudden jumps usually mean a new pool was lazy-initialised; expected during warmup. |

When evaluating an optimisation, **screenshot the same panel before
and after**. Numerical p95 isn't the only story — sometimes the gain
is "GC dropped from 12% of wall time to 3%" which only the time-series
shows.

### Drilling into Jaeger

```bash
make jaeger      # opens http://localhost:16686
```

1. Pick a service from the dropdown — `gateway` is usually the entry point.
2. Click "Find Traces", set "Min Duration" to filter the long tail.
3. Open a slow trace; the span tree shows where wall-clock time went.

Useful patterns:

- **Gateway → game-service span dominates** → the slow operation is
  in game-service. Drill into its spans for the specific RPC.
- **gateway-only span dominates** → middleware (JSON, auth, …) is
  slow. Look at where allocation pressure is.
- **Gap in the trace** → the request was queued or waiting on
  back-pressure. Compare timestamps between adjacent spans.

> Currently, gateway → game-service traces aren't joined because
> gRPC tracing isn't wired (see `docs/performance.md` § Deferred
> work). Until that lands, the two halves show up as separate traces.

### Reading an async-profiler flame graph

```bash
# In one shell — stress the target while you profile:
PEAK_USERS=100 HOLD_SECONDS=60 sbt 'gatling/Gatling/testOnly chess.gatling.StressSimulation'

# In another — attach for 60 seconds:
make profile-async-cpu SERVICE=game-service DURATION=60

# Output lands in perf-reports/profiles/async-cpu-game-service-<ts>.html
open perf-reports/profiles/async-cpu-game-service-*.html
```

Reading the flame graph:

- Width = % of CPU time spent in that frame.
- Climb to a wide green frame; that's the hot method.
- Java/Scala frames are mixed with JVM internals. Filter
  by the package prefix (`chess.*`) to focus on app code.

What to look for as optimisation targets:

| Wide frame | Likely cause | Try |
|---|---|---|
| `String.intern` / `String.<init>` | String allocation pressure | Cache parsed strings, intern hot strings |
| `java.util.HashMap.put` | Map churn (often in JSON encoding) | Specialised codec, persistent map |
| `scala.collection.immutable.List.::` | Cons-cell hot loop | Use `Array` / `Vector` for the hot path |
| `zio.internal.FiberRuntime.evaluateEffect` | ZIO scheduling overhead | Coalesce small effects, reduce `flatMap` chains |
| `Method.invoke` | Reflection (often from a JSON codec) | Specialised codec or compile-time derivation |

Use the alloc profiler (`make profile-async-alloc`) for "what is
allocating" rather than "what is computing" — different surface, often
finds different things.

---

## Extending the experiments

### Adding a new backend

Goal: drop a new backend into the matrix with no further plumbing.

1. **Create a module** at `persistence/<name>/` mirroring an existing
   one (e.g. `persistence/postgres/`). Implement the two repositories
   against the contract in `persistence/api`.
2. **Add a `Backend.<Name>`** case in `persistence/api/.../Backend.scala`
   and a parse arm in `BackendConfig`.
3. **Wire it into `PersistenceLayers`** at
   `persistence/runtime/.../PersistenceLayers.scala` — one new case
   each in `primaryGameRepository` and `primaryLobbyRepository`.
4. **Add a compose service block** in `docker-compose.yml` with
   `profiles: ["<name>"]`.
5. **Add `make stack-<name>`** in `Makefile` — three-line copy from
   the existing `_stack_up` macro.
6. **Add `caches_for_backend` arm** in `scripts/db-matrix.sh` if the
   new backend can host a cache.

The matrix picks the new backend up automatically when you pass
`BACKENDS=<name>` (or include it in the default list).

### Adding a new optimisation selector

The pattern in each affected service Main:

```scala
// In e.g. game-service/.../GameServiceMain.scala
import chess.opt.{LegalMovesSelector, LegalMovesStrategy}

val legalMovesLayer: ZLayer[Any, Nothing, MoveValidator] =
  LegalMovesSelector.fromEnv

program.provide(
  legalMovesLayer,
  ...
)
```

Where the selector is defined in a small module (one per
optimisation):

```scala
// chess/opt/LegalMovesSelector.scala
object LegalMovesSelector:
  enum Strategy:
    case Memo, Recompute

  val fromEnv: ZLayer[Any, Nothing, MoveValidator] =
    ZLayer.fromZIO(
      System.env("PICHESS_OPT_LEGAL_MOVES").orDie.map {
        case Some("recompute") => Strategy.Recompute
        case _                 => Strategy.Memo   // default + invalid → optimised
      }
    ) >>> (
      Strategy match
        case Strategy.Memo      => MoveValidatorMemo.layer
        case Strategy.Recompute => MoveValidatorRecompute.layer
    )
```

`PICHESS_OPT_ALL=naive` is handled inside `fromEnv` as the override:
if it's set to `naive`, every selector falls back to its naive case.

### Adding a Gatling simulation or k6 surface

- **Gatling sim**: drop a new file under
  `gatling/src/test/scala/chess/gatling/<Name>Simulation.scala`
  following the existing pattern. The runner accepts the suffix via
  `MODE=<Name>`.
- **k6 surface**: drop a new script under
  `k6/scripts/<surface>/<name>.js`. Add the surface to
  `scripts/k6-run.sh`'s case statement. Add Makefile targets if you
  want the standalone make hook.

---

## The generated report

`scripts/perf-report.sh` (called via `make perf-report`) reads any
`perf-reports/<TS>/` directory and emits
`performance-test-results.md` next to the raw data.

```bash
# Most recent run dir, picked automatically:
make perf-report

# Explicit:
make perf-report RUN_DIR=perf-reports/20260603T101500Z
```

The report assembles whatever's in the run dir:

| Section | Source | Shown when |
|---|---|---|
| Run metadata | `git rev-parse`, `uname`, `docker version` | Always |
| DB matrix | `matrix/<cfg>/<workload>/summary.txt` | A `matrix/` dir exists |
| Resource profile | `matrix/<cfg>/<workload>/prometheus-*.json` | A `matrix/` dir exists |
| Single-run Gatling | `<backend>/summary.txt` | No matrix; perf-run.sh output |
| k6 surfaces | `k6/<surface>/summary.json` | A `k6/` dir exists |
| JMH microbenchmarks | `bench.json` (or `../bench-*.json`) | Either form present |
| Raw artifacts | walk the run dir | Always |

Missing sections are gracefully skipped — the report renders cleanly
against any subset.

---

## Reproducing published results

### Hardware notes

The reference rig is a MacBook Air M2 (16 GB RAM, macOS 14). Numbers
will shift on different hardware (Linux servers will see lower
Chromium startup overhead for `make k6-browser`, Intel Macs will see
higher); the **relative ordering** between backends and the
**optimised-vs-naive delta** should hold.

### Pre-flight checklist

```bash
# 1. Docker Desktop running, ≥10 GB allocated.
docker info | grep -i memory

# 2. All images built locally.
make build

# 3. k6 image built (xk6 + Chromium).
make k6-build

# 4. Clean state (no leftover containers from previous runs).
make stack-down 2>/dev/null
docker compose --profile postgres --profile mongo --profile redis \
               --profile cassandra --profile opening --profile analytics \
               --profile obs --profile k6 down
```

### The canonical command sequence

```bash
# Persistence experiment (≈ 30-50 min)
make db-matrix

# Performance experiment — full A/B (≈ 60-90 min)
make perf-all                                # default = optimised
PICHESS_OPT_ALL=naive make perf-all          # baseline = naive

# Generate the report for the most recent run
make perf-report
open perf-reports/$(ls -t perf-reports | head -1)/performance-test-results.md
```

### What to commit / share

- `performance-test-results.md` — the headline report.
- `matrix.md` + `matrix-summary.csv` — the raw matrix data.
- Per-config `summary.txt` files — small enough to read.
- Gatling HTML reports — useful for deep dives but bulky; share by
  directory tar or upload to the dev page via `make perf-bake`.

`perf-reports/` is gitignored — every run starts clean and the
artifacts are reproducible.
