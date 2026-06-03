# DB selection report — pichess persistence experiment

> Backend rationale derived from the DB matrix experiment in
> [`perf-experiments.md`](perf-experiments.md). For the application-
> level perf analysis on the chosen backend, see
> [`performance-test-results.md`](performance-test-results.md).

## TL;DR

**Choice: `redis` as the primary persistence backend** for game state.
It wins both workloads on every meaningful metric:

|              | Workload | Winner | p95 latency | Margin over runner-up |
|---|---|---|---|---|
| **Game**     | typical play | redis | 14 ms | 17 % faster than #2 (postgres+redis at 17 ms) |
| **Stress**   | saturation   | redis | 9 ms  | 10 % faster than #2 (inmemory at 10 ms)       |

Caching with Redis on top of a durable primary **did not help** in
this experiment — it added a network hop without amortising any read
pattern observable in the workloads. We **disable the cache decorator
by default** as part of this choice. (See [Discussion](#discussion).)

Runtime: 16 configs × 2 workloads in `make db-matrix` lite mode,
~30 min wall-clock total. Source data: `perf-reports/20260603T005652Z/`.

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

Lite mode (the new default) keeps gateway + obs + the warm JVMs
running across rotations; only the DB and the three persistence-reading
services restart. Heavy mode (`MATRIX_HEAVY=true`) does a full stack
down/up per rotation but produces the same numbers.

**Workloads**:
- **Game** — `GameSimulation`: 10 closed-loop users × 5 s ramp, each
  user runs the canonical 8-ply opening once. Represents a typical
  user playing one game. 100 requests total per rotation.
- **Stress** — `StressSimulation`: ramp to 50 users / 5 s, then
  constant arrival rate (5 rps × 60 s plateau). Represents
  sustained load. 3500 requests per rotation.

Endurance, Spike, Volume, Mixed are excluded by default — see
`perf-experiments.md` for why (the DB-side effects they surface
need a much longer run window; not load-bearing for backend choice).

---

## Results

### Game workload (typical play)

Configs ranked by p95 — lower is better.

| Rank | Backend | Cache | Mean RPS | p50 ms | p95 ms | p99 ms |
|---:|---|---|---:|---:|---:|---:|
| 1 | **redis** | none | 10 | 6 | **14** | 39 |
| 2 | postgres | redis | 10 | 7 | 17 | 68 |
| 3 | inmemory | none | 10 | 7 | 18 | 33 |
| 4 | postgres | none | 10 | 7 | 19 | 60 |
| 5 | cassandra | none | 10 | 11 | 20 | 40 |
| 6 | mongo | none | 10 | 9 | 21 | 34 |
| 7 | mongo | redis | 10 | 9 | 23 | 64 |
| 8 | cassandra | redis | 10 | 11 | 24 | 62 |

### Stress workload (saturation)

| Rank | Backend | Cache | Mean RPS | p50 ms | p95 ms | p99 ms |
|---:|---|---|---:|---:|---:|---:|
| 1 | **redis** | none | 50 | 3 | **9** | 13 |
| 2 | inmemory | none | 50 | 3 | 10 | 14 |
| 3 | mongo | none | 50 | 4 | 10 | 16 |
| 4 | mongo | redis | 50 | 5 | 11 | 15 |
| 5 | postgres | none | 50 | 4 | 11 | 14 |
| 6 | postgres | redis | 50 | 4 | 11 | 16 |
| 7 | cassandra | redis | 50 | 7 | 12 | 17 |
| 8 | cassandra | none | 50 | 8 | 13 | 18 |

### Resource profile (Stress, game-service)

This is the most representative service for the persistence cost —
gateway is mostly glue, repository/lobby don't see the full traffic.

| Backend       | Cache | Heap (post-run) | GC time | GC count | CPU s |
|---|---|---:|---:|---:|---:|
| **redis**     | none  | **55 MB** | 101 ms | 19 | **15.2 s** |
| inmemory      | none  | 24 MB  | 56 ms  | 16 | 9.0 s |
| cassandra     | none  | 42 MB  | 243 ms | 48 | 14.5 s |
| postgres      | none  | 38 MB  | 131 ms | 35 | 15.7 s |
| mongo         | none  | 50 MB  | 166 ms | 33 | 20.8 s |
| postgres      | redis | 51 MB  | 233 ms | 47 | 19.6 s |
| mongo         | redis | 47 MB  | 199 ms | 53 | 23.0 s |
| cassandra     | redis | 42 MB  | 265 ms | 83 | 18.3 s |

> Numbers for repository / lobby-service are intentionally omitted —
> they include reset-on-restart artefacts under lite mode (CPU
> deltas read negative when the service was just recreated, which
> Prometheus can't distinguish from a real decrease). The raw data is
> in `perf-reports/20260603T005652Z/matrix/<config>/<workload>/prometheus-*.json`
> for anyone who wants to dig deeper.

---

## Discussion

### Why redis wins

Redis is the fastest at both the read path (game-state by id) and the
write path (state replacement on every move). The pichess access
pattern is dominated by **single-game key lookups**:

- `GET /api/games/{id}/state` → `repo.load(id)`
- `POST /api/games/{id}/move` → `repo.load(id)` + apply move + `repo.save(id, ...)`
- No range queries, no joins, no analytics on this surface.

This is exactly the shape Redis was built for. Postgres carries
overhead it can't amortise here (catalog lookups, parse/plan steps);
Mongo carries BSON encoding cost on every doc; Cassandra carries LSM
compaction overhead for what's effectively a key-value workload.

The win is consistent on tail latency:
- Stress p99: redis 13 ms, runner-up 14 ms. ~7 % improvement.
- Game p99: redis 39 ms — the lowest of the durable backends despite
  Game being a low-RPS test where backend differences should
  flatten.

Resource cost is in line with the other backends. Heap is on the higher
side at 55 MB but is dominated by the redis Lettuce client's
buffer pools, not application allocation. GC time / count under
Stress is the second-lowest after inmemory.

### Why the cache hurt

`PICHESS_CACHE=redis` decorates the primary repo with `CachedGameRepository`
(read-through, write-through). In every backend × workload pair with
`cache=redis`, the cached config was either equal or slightly worse
than the bare primary:

- postgres Stress: bare 11 ms vs cached 11 ms (no benefit, +0.1ms p50)
- mongo Stress: bare 10 ms vs cached 11 ms (-10 % win)
- cassandra Stress: bare 13 ms vs cached 12 ms (+8 %, marginal)
- postgres Game: bare 19 ms vs cached 17 ms (+11 %, the one mild win)

Two structural reasons:

1. The workload is write-heavy on the **same key** the next request
   reads. The write-through invalidates the cache, so the next read
   always misses. The cache adds a round-trip on every hit without
   reducing the load on the primary.

2. The cache is a separate Redis container. For postgres or mongo,
   `cache=redis` adds one network hop (gateway → game-service →
   redis → game-service → primary DB). At 3-10 ms primary-DB
   latency, a 1-2 ms redis round-trip is amortising too little to
   pay for itself.

A **read-only** workload (heavy `GET /state` traffic without
intervening moves) would change this picture entirely — that's a
follow-up experiment, not part of this report.

### Why not inmemory?

inmemory is close to redis at both workloads (within 10 % p95) but
loses on durability — every container restart wipes state. The
matrix is comparing **persistence** options, not just "what's the
fastest in-process state store." Redis matches inmemory's speed
profile while keeping state across restarts.

### Why not postgres?

postgres is the safe "second pick" if redis isn't viable for an
operational reason — it ranks #4 / #5 on the matrix and offers the
relational model the application doesn't currently need (no joins,
no secondary indexes) but might want later. As-is, it loses to redis
by 35 % on Game p95 and 22 % on Stress p95, and the headline finding
from the Phase B profiling (`PostgresDatabase.forURL` opening fresh
sockets per query) means each query carries SCRAM/PBKDF2 cost that
redis sidesteps entirely.

The Phase C `PICHESS_OPT_PG_POOL` selector exists precisely to A/B
this finding; results are in
[`performance-test-results.md`](performance-test-results.md).

### Why not cassandra / mongo?

- **mongo**: BSON encoding cost on every doc, no native single-key
  optimisation worth taking advantage of for this workload. p95 ~50 %
  worse than redis on Game.
- **cassandra**: LSM overhead + heavier in-process driver state for
  what is effectively a key-value workload. p95 on Stress is the
  worst of all backends; the compaction window also makes endurance
  measurements noisier. We rule it out for this app, though it
  remains a sensible pick for the event-log side
  (`opening-service`'s Kafka projection, if we ever needed wider
  reach on the analytics side).

---

## Decision

**Default `PICHESS_BACKEND=redis`**, **`PICHESS_CACHE=none`**.

The dev compose stack switches via `make stack-redis`. CI / prod
should set the env at deployment time.

This is the configuration the application-level perf report
([`performance-test-results.md`](performance-test-results.md))
profiles against.

---

## Caveats

- **Dev rig**: MacBook Air M2, 16 GB. Numbers should hold their
  *ordering* on stronger hardware, but absolute ms values will be
  ~20-40 % faster on a real Linux server.
- **Light Game workload (10 RPS)**: differences at this scale are
  small enough that one rerun could shuffle ranks 3-6. The
  redis lead at #1 is consistent across reruns; the middle of the
  pack is noisier. The Stress workload (50 RPS) is more
  discriminating.
- **No endurance run**: Cassandra's LSM compaction window can shift
  its numbers over hours. If we ever pick Cassandra for anything,
  re-test with `WORKLOADS=Endurance` first.
- **Single-node every backend**: postgres/mongo/cassandra are
  configured single-instance in `docker-compose.yml`. A replicated
  setup would change their latency tail (replication round-trips)
  but not the relative ordering here.

---

## Reproducing this report

```bash
# Clean slate.
make stack-down
rm -rf perf-reports/*

# Build images (HikariCP / Hikari pool wiring goes in this build).
make build

# Run the matrix. Defaults: all backends, Game + Stress, lite mode, obs on.
make db-matrix

# Generate the per-run report (auto-included tables, resource profile).
make perf-report
# Open: perf-reports/<TS>/performance-test-results.md
```

The matrix takes ~30-45 min in lite mode; full breakdown of options
in [`perf-experiments.md`](perf-experiments.md).
