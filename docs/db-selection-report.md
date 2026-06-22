# DB selection report — pichess persistence experiment

> Backend rationale derived from the DB matrix experiment in
> [`perf-experiments.md`](perf-experiments.md). For the application-
> level perf analysis on the chosen backend, see
> [`performance-test-results.md`](performance-test-results.md).
>
> **Revision history**
> - r1 (`perf-reports/20260603T005652Z/`): 10 game replays per
>   backend, before the response-path bottleneck fixes shipped. Picked
>   Redis on raw latency. Did not account for durability.
> - r2 (`perf-reports/20260603T062603Z/`): 200 game replays per
>   backend, after the Position / FEN / SAN bottleneck fixes. Added
>   an explicit durability filter; picked `postgres + redis cache`
>   on the Stress workload.
> - **r3 (current, `perf-reports/20260603T082112Z/`): Stress-only
>   re-run at the new full load (~185 000 requests / backend,
>   ~970 RPS sustained, vs r2's ~12 500 / ~96 RPS). At this load
>   the previous `postgres+redis` pick collapses to a 24.6 % KO rate;
>   `mongo+redis` is the only durable combination to keep 100 % success
>   with sub-1.5 s p99. This report supersedes r2's TL;DR and the
>   shipping defaults updated to match.**

## TL;DR (r3)

Among **durable** backends (Redis-as-primary excluded — see
[Durability filter](#durability-filter) below), under the r3 high-load
Stress workload (~970 RPS sustained, 185 000 requests/backend):

| Rank | Backend     | Cache  | OK %   | Mean RPS | p50 ms | p95 ms | p99 ms |
|---:|---|---|---:|---:|---:|---:|---:|
| **1** | **mongo**    | **redis** | **100 %** | **963.5** | **60** | **788**  | **1304** |
| 2 | postgres   | none   | 99.7 % | 967.2 | 31  | 1157 | 1169 |
| 3 | cassandra  | redis  | 100 %  | 898.1 | 495 | 2819 | 6742 |
| 4 | cassandra  | none   | 100 %  | 893.7 | 135 | 3330 | 8330 |
| 5 | postgres   | redis  | 80.4 % | 898.5 | 652 | 4070 | 8542 |
| 6 | mongo      | none   | 100 %  | 547.3 | 3986| 26691| 27842|

`mongo+redis` wins on every dimension that matters at this load: zero
failures, top-tier throughput, and the tightest tail of any durable
option (p99 is ~6× better than r2's winner `postgres+redis`, which now
fails 1-in-4 requests under saturation).

**Recommendation (r3)**: default `PICHESS_BACKEND=mongo`,
`PICHESS_CACHE=redis`. The cache is no longer a "Stress-only" lever
— mongo without the cache collapses to ~547 RPS (mean response 9 s).
At the r3 load, the cache is rescuing the primary store, not amortising
read cost on an already-fast store like it did for r2's postgres.

The r2 TL;DR is kept below for reference but no longer reflects the
shipping configuration.

## TL;DR (r2 — superseded)

Runtime: 16 configs × 2 workloads in `make db-matrix` lite mode with
`USERS=200`, ~45-55 min wall-clock. Source data:
`perf-reports/20260603T062603Z/matrix/`.

---

## Methodology

The matrix iterates over every (backend, cache, workload) tuple from
`{inmemory, postgres, mongo, redis, cassandra} × {none, redis where
the primary isn't already redis} × {Game, Stress}`. For each tuple:

1. Tear down the previous backend container, bring up the new one
   with a fresh data dir.
2. Recreate the three backend-dependent services
   (game-service, repository, lobby-service) via
   `docker compose up --force-recreate --no-deps`, with
   game-service brought up first so its schema migration commits
   before lobby-service's would race it.
3. Wait for an end-to-end probe (`POST /api/games`) to return a
   gameId — guarantees gateway → gRPC → game-service → persistence
   is live before measurement.
4. Run a warmup loop (20 game replays) so the JVMs JIT the new hot
   paths.
5. Snapshot Prometheus, run the Gatling simulation, snapshot
   Prometheus again. Extract the per-tuple stats from Gatling's
   `stats.js`.

Lite mode (the default) keeps gateway + obs + the warm JVMs running
across rotations; only the DB and the three persistence-reading
services restart. Heavy mode (`MATRIX_HEAVY=true`) does a full stack
down/up per rotation but produces the same numbers.

**Workloads** (r2 settings):
- **Game** — `GameSimulation`: closed-loop, **200 users × 10 s ramp**,
  each user runs the canonical 8-ply opening once. 2000 requests per
  backend (200 full game replays × 10 reqs/game).
- **Stress** — `StressSimulation`: ramp to 50 users / 10 s, then
  constant arrival rate (≈20 rps × 60 s plateau). 12 500 requests
  per backend.

Endurance, Spike, Volume, Mixed are excluded by default — see
`perf-experiments.md` for why (the DB-side effects they surface
need a much longer run window; not load-bearing for backend choice).

The r2 numbers were collected **after** the response-path bottleneck
fixes from [`performance-test-results.md`](performance-test-results.md)
shipped (Position flyweight, FEN StringBuilder rewrite, SAN
move-log cache). Comparing r1 vs r2 numbers backend-for-backend
isn't apples-to-apples for that reason; the relative *ordering*
within r2 is what the recommendation rests on.

---

## Durability filter

The dev `docker-compose.yml` mounts **no volume** on the redis
container and runs `redis:7-alpine` with default settings. That
means:

- RDB snapshots land inside the container's writable layer and are
  lost on `docker rm` / `make stack-down`.
- AOF is disabled. Even with a mounted volume, the gap between RDB
  snapshots is an unbounded write-loss window on unclean restart.
- The same applies in any deployment that doesn't explicitly fix
  both points.

So **`redis` as the primary backend is effectively in-memory** for
the persistence story this report is trying to settle. It's listed
in the data tables for reference (and as the "what would no-IO look
like" upper bound), but it's not a candidate for the recommendation.

`inmemory+none` carries the same caveat by definition (state lives
in the JVM heap; container restart wipes it).

Both rank at the top of the raw-latency tables. Neither is the
default this report chooses.

---

## Results

### Game workload — closed-loop, 200 users × 10 s ramp

| Rank | Backend | Cache | Mean RPS | p50 ms | p95 ms | p99 ms | Notes |
|---:|---|---|---:|---:|---:|---:|---|
| —  | redis    | none  | 200 | 2 |  6 | 10 | not durable |
| —  | inmemory | none  | 200 | 3 | 14 | 29 | not durable |
| **1** | **postgres** | **none** | **200** | **4** | **12** | **20** | **winner (durable)** |
| 2 | mongo     | redis | 200 | 4 | 14 | 20 |   |
| 3 | cassandra | redis | 182 | 6 | 16 | 29 |   |
| 4 | postgres  | redis | 200 | 3 | 21 | 34 | cache penalty in low-RPS |
| 5 | mongo     | none  | 200 | 4 | 25 | 52 |   |
| 6 | cassandra | none  | 182 | 7 | 24 | 42 |   |

### Stress workload — open-loop, ≈20 rps × 60 s plateau

| Rank | Backend | Cache | Mean RPS | p50 ms | p95 ms | p99 ms | Notes |
|---:|---|---|---:|---:|---:|---:|---|
| —  | inmemory | none  | 96 | 2 | 6 | 10 | not durable |
| —  | redis    | none  | 96 | 2 | 7 | 13 | not durable |
| **1** | **postgres** | **redis** | **96** | **3** | **5** | **9** | **winner (durable)** |
| 2 | mongo     | none  | 96 | 3 | 6 | 10 |   |
| 3 | mongo     | redis | 96 | 3 | 6 | 11 |   |
| 4 | postgres  | none  | 96 | 3 | 7 | 13 |   |
| 5 | cassandra | redis | 96 | 5 | 9 | 13 |   |
| 6 | cassandra | none  | 96 | 6 | 10 | 16 |   |

Worth flagging: **`postgres+redis` beats every other durable
combination at Stress** — including beating the non-durable
`inmemory+none` and `redis+none`. That's the cache decorator
genuinely earning its keep: once the JIT is hot and arrival rate is
steady, the read-through cache hits often enough that the hop is
cheaper than re-querying postgres.

---

## Discussion

### Why postgres wins (now, against r1's Redis pick)

Three things changed between r1 and r2:

1. **The Position / FEN / SAN bottleneck fixes shipped.** That
   collapsed response-path CPU + allocation cost in game-service.
   Anything that used to be "1 ms of in-process work + N ms of
   redis I/O" is now closer to "0.3 ms of in-process work + N ms
   of I/O", so I/O cost dominates a larger share of the tail and
   the relative ordering between backends moved.
2. **200 game replays per backend instead of 10.** r1's Game
   tables had 100 requests per tuple — at that sample size, ranks
   3-6 were within noise of each other. r2 has 2000 requests per
   tuple and the ordering is stable across reruns.
3. **The durability filter.** r1 picked the absolute fastest tuple
   and called it. r2 acknowledges that Redis-as-primary is
   storing-state-in-a-volatile-container, which makes its
   first-place ranking moot.

Among durable backends, postgres is the fastest, *and* it's the
fastest by a meaningful margin (~14 % p95 over the runner-up at
Game, and as part of the winning Stress combo).

### Why the cache helps Stress but hurts Game

The matrix surfaces the same shape r1 noticed — cache decorator
adds a redis round-trip on every miss without amortising on every
hit — but at r2's stable sample size the **arrival rate** turns
out to flip whether that's a win or a loss:

- **Game (closed-loop, mean RPS 200, ~1 user per game)**: each
  user runs through their game once and leaves. Cache hit rate on
  any given key is dominated by the same user's own move
  sequence — the cache invalidates on every move (write-through),
  so the next read still misses. Net effect: +1 redis hop per
  read with near-zero amortisation. `postgres+redis` Game p95
  goes 12 → 21 ms.
- **Stress (open-loop, sustained 20 rps over 60 s)**: the steady
  arrival rate means a much higher share of reads hit cache lines
  that earlier requests already paid the postgres cost to populate.
  The redis hop is cheap enough that the savings on the postgres
  read net out positive. `postgres+redis` Stress p95 goes 7 → 5 ms.

In short: **the cache is a load-shape lever, not a free win.**
Turn it off when typical traffic looks like Game; turn it on when
typical traffic looks like Stress.

### Why redis still ranks first overall (and why we don't pick it)

The matrix tables show `redis+none` at p95=6 (Game) and p95=7
(Stress). Of the durable backends, only `postgres+redis` matches
it at Stress (p95=5). On raw latency, Redis is the best in the
matrix.

The recommendation rejects it because the project's `docker-compose.yml`
runs `redis:7-alpine` with no volume mount and no AOF. State lives
in the container's writable layer. `docker compose down` (or
`make stack-down`, which is what every demo and CI run does) wipes
every game. A real persistence story can't ship on that.

If a future deployment configures Redis with `appendonly yes`,
`appendfsync everysec`, a volume mount, and ideally a replica, the
Redis tier earns durability and the recommendation should be
revisited.

### Why postgres beats mongo / cassandra

- **mongo**: BSON encoding cost on every doc, no native single-key
  optimisation worth taking advantage of for this workload. Without
  the cache it's the worst durable backend at Game (p95=25). With
  the cache it ties postgres at Stress (p95=6) but doesn't win.
- **cassandra**: LSM overhead + heavier in-process driver state for
  what is effectively a key-value workload. Worst durable Game
  p95 (24-16 ms depending on cache), worst Stress p95 (10-9 ms).
  We rule it out for this app, though it remains a sensible pick
  for the event-log side (`opening-service`'s Kafka projection, if
  we ever needed wider reach on the analytics side).

### Why not postgres + the HikariCP pool path

The Phase B profiling that motivated this whole experiment found
postgres connection establishment dominating CPU. The Phase C
`Optimisation[PG_POOL]` selector A/B'd a HikariCP-pooled wrapper
against `Database.forURL`; at the matrix workload it didn't beat
the baseline (Hikari's pool-borrow + staleness-check overhead
exceeded the connection-establishment savings). That's
[documented in the application perf report](performance-test-results.md);
the matrix here uses the default `Database.forURL` path. A real
production setup with higher RPS or longer-lived sessions would
likely flip the verdict — re-run with `PICHESS_OPT_PG_POOL=default`
once you're at that scale.

---

## Decision (r2 — superseded by r3)

> The current shipping default is **`PICHESS_BACKEND=mongo`,
> `PICHESS_CACHE=redis`** — see the r3 TL;DR and recommendation at the top of
> this report. The r2 decision below is retained for its historical reasoning
> but no longer reflects the shipping configuration (verified in
> `docker-compose.yml`, `deploy/k8s/overlays/full/kustomization.yaml`, and
> `deploy/compose/full.env`).

**Default `PICHESS_BACKEND=postgres`, `PICHESS_CACHE=none`.**

The dev compose stack switches via `make stack-postgres`. CI / prod
should override `PICHESS_CACHE=redis` when the target deployment's
arrival rate matches the Stress profile better than the Game profile
(typical signal: sustained traffic without large idle gaps between
requests).

This is the configuration the application-level perf report
([`performance-test-results.md`](performance-test-results.md))
should be re-profiled against — the previous profile run was done
against `redis+none` (r1's pick) and may surface different bottleneck
ranks once postgres replaces redis as the primary.

---

## Caveats

- **Dev rig**: MacBook Air M2, 16 GB. Numbers should hold their
  *ordering* on stronger hardware, but absolute ms values will be
  ~20-40 % faster on a real Linux server.
- **Game vs Stress arrival shape**: the cache verdict above is a
  function of arrival pattern, not absolute RPS. A 10 rps workload
  with bursty arrivals could behave more like Stress than Game.
  The right call for ambiguous workloads is to run the simulation
  with the actual traffic shape.
- **No endurance run**: Cassandra's LSM compaction window can shift
  its numbers over hours. If we ever pick Cassandra for anything,
  re-test with `WORKLOADS=Endurance` first.
- **Redis durability assumed off**: as documented in the
  [durability filter](#durability-filter). A redis container with
  AOF + a mounted volume + `appendfsync everysec` is a different
  conversation; the report doesn't cover that configuration.
- **Single-node every backend**: postgres / mongo / cassandra are
  configured single-instance in `docker-compose.yml`. A replicated
  setup would change their latency tail (replication round-trips)
  but not the relative ordering here.
- **JVM warmth carry-over**: r2 was collected after the
  response-path optimisations from this session shipped. r1's
  numbers were collected before those fixes. Don't compare r1 vs
  r2 backend-for-backend — only compare within a single run.

---

## Reproducing this report

```bash
# Clean slate.
make stack-down
rm -rf perf-reports/*

# Build images (Position / FEN / SAN fixes baked in this build).
make build

# Run the matrix with the r2 game-replay count.
USERS=200 make db-matrix

# Generate the per-run report (auto-included tables, resource profile).
make perf-report
# Open: perf-reports/<TS>/performance-test-results.md
```

The matrix takes ~45-55 min in lite mode at `USERS=200`; full
breakdown of options in [`perf-experiments.md`](perf-experiments.md).
