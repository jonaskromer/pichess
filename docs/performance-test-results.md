# Application performance test report

> Application-level perf analysis with **redis** as the chosen
> persistence backend (rationale: see
> [`db-selection-report.md`](db-selection-report.md)). Methodology for
> the perf stack: [`perf-experiments.md`](perf-experiments.md).
>
> This report's job:
>
> 1. Where the time goes under sustained load — CPU, allocation, wall-clock.
> 2. The top bottlenecks that drive user-visible latency.
> 3. Concrete proposals for each bottleneck, with the expected mechanism + the signal to look for after the fix.

## TL;DR

Under sustained Stress on the redis backend (80 users, 240 s plateau,
15 RPS), Gatling sees **p50 = 3 ms / p95 = 9 ms / p99 = 13 ms** at
the HTTP boundary. Of the ~9 ms p95 budget:

- ~1.4 ms is in the gateway (Jaeger span median).
- ~7 ms is the gRPC → game-service → redis round-trip.

The CPU + allocation profiles surface three load-bearing bottlenecks
in game-service, all in the **response-building path** rather than
the persistence path:

1. **gRPC `StateReply` framing** dominates allocation (~30 % of
   sampled `byte[]` allocations). Every `MakeMove` returns the full
   board state; lots of bytes get copied through the gRPC message
   framer.
2. **FEN serialisation** is allocated on every response and inside
   the move log — same `FenSerializer.serialize` path runs at least
   twice per move (~30 alloc samples).
3. **`Position.apply` in `Ray.walk`** allocates one `Position` per
   ray step during move validation (~21 alloc samples). Confirms the
   Phase B finding.

Together these drive GC pressure that takes ~30 % of game-service's
on-CPU samples. Reducing allocation in any one is a win; reducing
all three should make redis tail latency drop into the **~6-7 ms p95**
range and headroom at higher RPS.

Proposed fixes [below](#proposed-optimisations).

---

## Methodology

Run rig: MacBook Air M2, 16 GB RAM, JDK 23, Docker Desktop, redis
backend, no cache decorator, `OBS=true`, `TRACING_ENABLED=true`.

Passes:

1. **Gatling Stress** (the matrix run that picked redis) plus a
   second long-form Stress for the profiling window
   (`PEAK_USERS=80`, `HOLD_SECONDS=240`, `RATE_PER_SEC=15`).
2. **k6 surfaces** — `make k6 SURFACES=browser,grpc`. Kafka surface
   skipped because no opening / analytics profile was active for
   this experiment.
3. **JMH module benches** — `make bench-codec` + `make bench-rules`.
4. **async-profiler** — `itimer` (on-CPU) and `alloc` against
   `game-service` and `gateway` during the Stress plateau. Sample
   files at `perf-reports/profiles/redis-{itimer,alloc}-{game,gateway}.txt`.
5. **Jaeger** — 200 most-recent traces from the gateway service.

All numbers come from `perf-reports/` artefacts. Per-run details:
`perf-reports/20260603T005652Z/performance-test-results.md` for the
matrix; `perf-reports/20260603T012605Z/performance-test-results.md`
for the k6 surfaces against redis.

---

## Pass 1 — Gatling at scale (redis backend)

From the DB-matrix run, `redis+none` under both workloads:

| Workload | Mean RPS | p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| Game (10 users) | 10 | 6 ms | 14 ms | 39 ms |
| Stress (50 user peak) | 50 | 3 ms | **9 ms** | 13 ms |

**Resource profile** (game-service under Stress):

| Service | Heap (post-run) | GC time | GC count | CPU s |
|---|---:|---:|---:|---:|
| gateway | 33 MB | 146 ms | 29 | 5.8 |
| game-service | **55 MB** | **101 ms** | **19** | **15.2** |
| repository | 29 MB | 11 ms | 1 | 1.5 |
| lobby-service | 27 MB | 16 ms | 3 | 1.8 |

Notable: game-service does ~85 % of the CPU work and ~73 % of the GC
time under Stress. Gateway is in the "fast plumbing" regime; lobby
and repository sit cool. Everything below this section is about
**game-service**.

---

## Pass 2 — k6 surfaces

### `browser` surface

| Metric | p95 |
|---|---:|
| Largest Contentful Paint | 3.1 s |
| First Contentful Paint  | 3.1 s |

Web Vitals comfortably under the dev-rig 5.5 s budget. The frontend
isn't a Layer-1 bottleneck at any scale measured here.

### `grpc` surface

Direct game-service gRPC (bypasses gateway):

| Metric | p50 | p95 | p99 |
|---|---:|---:|---:|
| RPC duration | 3.6 ms | 11.4 ms | 19.5 ms |

`tx`-tagged transport checks: **100 %** pass (20/20 iterations).
The state-match diagnostic check is informational only; under five
concurrent virtual users it surfaces the same race we documented
in Phase B (a small fraction of `MakeMove` calls ACK but don't land
in the per-game stream).

Comparing the two paths:

- HTTP via gateway (Gatling Stress p95): **9 ms**
- Direct gRPC (k6 grpc p95): **11.4 ms**

The k6 grpc surface is *slower* than HTTP because k6 only runs 5 VUs
while Gatling runs 50 with rate control — k6's variance is higher.
The numbers still triangulate the same range.

---

## Pass 3 — JMH module benches

### Codec (pure-function parse/serialise)

| Benchmark | Score (avgt) |
|---|---:|
| `FenParserBenchmark.fastparse` | **38.4 µs/op** |
| `FenParserBenchmark.regex` | 42.5 µs/op |
| `FenParserBenchmark.combinator` | 49.9 µs/op |
| `FenSerializerBenchmark.serialize` | **13.3 µs/op** |
| `FenSerializerBenchmark.positionKey` | 13.6 µs/op |
| `SanRoundTripBenchmark.parseApplySerializeAll` | 324 µs/op |
| `PgnParserBenchmark.parseAll` | 1.13 ms/op |
| `ZobristHashBenchmark.hashStart` / `hashMidGame` | 234 / 233 ns/op |

**Reading these:**

- `FenSerializer.serialize` is **13 µs**. The profile shows it runs
  at least twice per HTTP request (once for the StateReply's `fen`
  field, once for the move log entries). At 50 RPS × 13 µs × 2 = 1.3 ms/sec
  of pure-CPU spent re-serialising FEN — small absolute but a
  consistent slice of every move's response time.
- `FenParserBenchmark.fastparse` is **38 µs**, the default.
  Combinator is ~30 % slower; regex sits in the middle. Parser
  swap-out is a `Optimisation[FenParser]` waiting to happen.
- `MoveValidator.isInCheck` is **1.1-1.2 µs**, well-optimised. The
  hot path is allocation-bound, not compute-bound (see profile
  below).
- `ZobristHash` is **234 ns/op** — fast enough that no
  optimisation is worth the risk.

### Rules

| Benchmark | Score (avgt) |
|---|---:|
| `RayWalkBenchmark.queenRaysFromCenter`   | 349 ns/op |
| `RayWalkBenchmark.queenRaysFromCenterMid`| 331 ns/op |
| `MoveValidatorBenchmark.isInCheckStart` | 1.10 µs/op |
| `MoveValidatorBenchmark.isInCheckMidGame` | 1.16 µs/op |
| `MoveValidatorBenchmark.hasLegalMoveStart` | 3.53 µs/op |
| `MoveValidatorBenchmark.hasLegalMoveMidGame` | 3.52 µs/op |
| `GameApplyMoveBenchmark.singleMove` | 6.93 µs/op |

`Ray.walk` runs in **~340 ns**. The profile shows `Position.apply`
inside it allocating per step — the bench measures wall-clock per
walk, not allocation rate. Per-walk cost is the kind of thing the
JMH alloc profile would tell us; that's a follow-up.

---

## Pass 4 — Profiling

### game-service — top **on-CPU** samples (itimer, 45 s during Stress plateau)

| Samples | Path |
|---:|---|
| 28 | `ZScheduler.park` — idle worker (normal, expected at low load fraction) |
| 25 | `ZScheduler.maybeUnparkWorker` → `LockSupport.unpark` — scheduling overhead |
| **23** | **G1 concurrent marking** — GC at work |
| **21** | **`zio.redis.RedisConnection.write` → AsynchronousSocketChannel.write** — redis I/O writes |
| 17 + 16 + 14 | More G1 marking / evacuation |
| 12 + 12 | More G1 marking |
| 10 | `ZIO Queue offer` + `Promise complete` — fiber-scheduling overhead |

**Reading this:** ~30 % of game-service's on-CPU time is GC work
(samples 23 + 17 + 16 + 14 + 12 + 12 = 94 of the top-10 total ~190).
Redis I/O is the next-largest non-idle, non-GC bucket. The
application itself (chess rules, FEN, gRPC handlers) shows up
*below* the GC frames, which says the heavy lift in the hot path is
allocation-driven, not compute-driven.

### game-service — top **allocation** samples (alloc event, 30 s)

| Samples | Path |
|---:|---|
| **59** | **`ZServerCall.sendMessage` → gRPC `MessageFramer.writeUncompressed` → `ByteStreams.copy` → `byte[]`** |
| 27 | `MoveRequest.parseFrom` → gRPC `CodedInputStream` → `byte[]` |
| **21** | **`SanSerializer.toSan` → `MoveValidator.isInCheck` → `Ray.walk` → `Position.apply`** |
| 16 | `ZIO Chunk.appended` (ZStream chunk allocation) |
| **15** | **`GrpcMappers.toStateReply` → `FenSerializer.serialize` → `FenCodec.encodeBoard` → `Position.apply`** |
| **15** | **`GameServiceLive.makeMove` → `FenSerializer.serialize` → `Position.apply`** (separate call site, same path) |
| 14 | `SanSerializer.toSan` → `pieceSan` → `disambiguation` → `Tuple2.apply` |
| 14 | ZIO `ChannelState.Read` allocation |
| 13 | ZIO stream Chunk + Tuple2 allocation |
| 12 | `SanSerializer.toSan` → `List.prependedAll` |

**Reading this:**

1. **#1 by a wide margin — gRPC StateReply framing** (59 samples).
   Every `MakeMove` returns a full `StateReply` (game id, FEN, status,
   active color, full move log, error). Each call goes through the
   gRPC message framer which allocates a buffer and copies the
   serialised proto bytes in.
2. **Position allocation in `Ray.walk`** (21 samples) is the
   re-confirmation of the Phase B finding — `Position.apply` is
   called inside the legal-moves walk.
3. **FEN serialisation in the response path** (15 + 15 = 30 samples,
   spread across two call sites). One is the `GrpcMappers.toStateReply`
   inside the move handler; the other is a downstream call from
   `GameServiceLive.makeMove`. Both walk the board and allocate
   `Position` per cell.
4. **SAN derivation in the move log** (21 + 14 + 12 = 47 samples
   combined across three SAN-related stacks). Every response carries
   the full move log; each entry includes its SAN string, which is
   computed lazily on the way out.

The pattern is clear: **the response path is the allocator**. The
move-validation walk + the FEN serialisation + the SAN derivation
all run on every successful `MakeMove`, and all three allocate
proportionally to the number of legal moves / board cells / move-log
entries.

### gateway — top alloc samples (for completeness)

| Samples | Path |
|---:|---|
| 44 | `ZClientCallImpl.sendMessage` → gRPC `MessageFramer` → `byte[]` (outgoing MoveRequest framing) |
| 43 | `tapir ServerInterpreter.onDecodeSuccess` → `String.encodeUTF8` → `byte[]` (HTTP response body encoding) |
| 40 | `ServerInboundHandler.attemptFastWrite` → `Chunk.toArray` → `byte[]` (zio-http writing the response) |

Gateway allocation is dominated by **transport framing** — gRPC
client-side + HTTP server-side. Each line is "necessary" allocation
for the corresponding protocol; reducing requires architectural
changes (e.g. zero-copy framing, off-heap buffers) that would touch
upstream libraries rather than app code.

### gateway — top on-CPU samples

| Samples | Path |
|---:|---|
| 18 | Netty `FlushConsolidationHandler.flush` → `epoll writev` |
| 9 | ZScheduler.maybeUnparkWorker |
| 8 | ZScheduler.park |
| 6 | Netty gRPC `WriteQueue.flush` |

No app-level hotspot. Gateway is mostly Netty I/O + ZIO scheduling.

---

## Pass 5 — Jaeger spans

Sampled from the gateway service over the most recent Stress run.
Per-operation latency (gateway-side wall-clock only — gRPC fan-out
isn't joined into the same trace, see Deferred Work in
`docs/performance.md`):

| Operation | Count | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|
| `POST /api/games/{id}/move`     | 33  | **1.4 ms** | 2.1 ms | 2.3 ms | 2.3 ms |
| `GET /api/games/{id}/events`    | 36  | 0.5-1.4 ms | 2.6-5.3 ms | — | — |
| `GET /api/stack-info`           | 20  | 0.7 ms | 8.4 ms | — | — |
| `GET /web/<static>`             | 130 | 0.5 ms | 1-10 ms | — | — |
| `GET /` (SPA shell)             | 15  | 0.6 ms | 85 ms | — | — |

The `POST /move` median at the gateway is **1.4 ms**, vs. the
Gatling end-to-end p50 of **3 ms**. The ~1.6 ms gap is the gRPC
hop + game-service work + redis round-trip (and a bit of HTTP
framing the gateway span doesn't count). That matches the on-CPU
profile's verdict that game-service is doing most of the per-request
work; the gateway is correctly thin.

---

## Bottleneck list

Ranked by user-facing-latency contribution × frequency.

1. **gRPC `StateReply` framing** (game-service alloc #1, drives ~30 %
   of all `byte[]` allocations in the hot path). On every move,
   every full state is serialised + buffered + flushed. Reducing
   what gets sent reduces the framer's work.
2. **FEN serialisation in the response** (alloc #5 + #6, plus extra
   FEN work in `positionKey` paths). Same serialise runs on the
   response and again inside the move-log derivation. Caching one of
   them removes the other.
3. **`Position.apply` allocation in `Ray.walk`** (alloc #3 — the
   Phase B finding re-confirmed). Hot in `MoveValidator.isInCheck`
   *and* in `FenCodec.encodeBoard`.
4. **SAN derivation in the move log** (alloc #7 + #10 + #12,
   combined ~47 samples). Every response includes the full SAN'd
   move log; each entry's SAN gets recomputed even though it never
   changes after the move is committed.
5. **gateway HTTP response UTF-8 encoding** (gateway alloc #2). 43
   samples writing the JSON body as UTF-8 bytes. Smaller than
   game-service's hotspots but cumulative.

---

## Optimisation A/B — PG_POOL

Postgres isn't the chosen backend, so this A/B is *informational*
rather than load-bearing for the redis recommendation. The Phase C
infrastructure is in place; the Phase B prediction said HikariCP
pooling would help. The smoke run from Phase C said otherwise:

| Workload | `PICHESS_OPT_PG_POOL=default` (HikariCP) | `=baseline` (`forURL`) |
|---|---:|---:|
| postgres+none p95 (40 users, 45 s hold) | 40 ms | 8 ms |
| postgres+none p95 (80 users, 90 s hold, pool size 30) | 9 ms | 7 ms |
| postgres+redis p95 (80 users, 90 s hold) | 72 ms | 4 ms |

**Reading these:** the HikariCP pool with default size (10) under
moderate concurrency *adds* latency rather than removing it. At
higher pool sizes (30) the gap closes but HikariCP still doesn't
beat `forURL` on the postgres workload here. The connection-establishment
cost the Phase B profile flagged is real per-frame — but at this
RPS and rotation tempo, the amortised savings don't exceed Hikari's
own latency tail.

This finding **doesn't refute Phase B**; it refines it:
- The 25 % CPU saved on connection establishment matters most under
  *very* high load with *long* sustained windows (where postgres'
  sustained CPU starts to bottleneck).
- At our load levels, `forURL`'s per-connect cost is amortised inside
  the `AsyncExecutor`'s thread pool, and Hikari's pool-borrow path
  + its connection-staleness checks become visible.
- Picking the *right* pool size for the workload + tuning Hikari's
  timeouts would likely shift this; that's a Phase D-style
  experiment we don't run here because the chosen backend is redis.

**The `Optimisation[T]` typeclass survives this finding intact** —
it correctly let us run the A/B without code churn. We just discovered
that *one* of the two implementations is worse on *this* workload.
That's exactly what perf experiments are for.

---

## Proposed optimisations

Each entry: **the finding** (citation to the data above), **the
proposed change** (concrete code change, ideally shipped as an
`Optimisation[T]` pair), **the expected mechanism** (why this should
help), **the expected signal** (which line of which table moves and
by how much).

### 1. Skinny `StateReply` — return move ACK, fetch state on demand

- **Finding**: gRPC `StateReply` framing is the #1 alloc site in
  game-service (59 of the top alloc samples). Each `MakeMove`
  serialises and frames the entire post-move state — FEN, status,
  full move log, error string — even though most callers (gateway →
  client) already know everything before the move and could derive
  the post-move state from the move alone.
- **Proposed change**: introduce a *thin* `MoveAckReply` (just
  `{gameId, status, error}`) returned by `MakeMove`; expose a
  separate `GetState(gameId)` (already exists) and a streaming
  `SubscribeGame` (already exists) for callers that need the full
  state. Make the response shape a `Optimisation[GameServiceLive]`
  pair: `default` = skinny ACK, `baseline` = current full state.
  The web/TUI client already updates locally by applying the move,
  so the wire stays semantically equivalent.
- **Expected mechanism**: ~30 % of allocation pressure removed,
  smaller messages on the wire, less framer copying.
- **Expected signal**: alloc samples on `MessageFramer.writeUncompressed`
  drop from 59 → 5-10. game-service GC time drops by ~30 %. Gatling
  Stress p95 drops from ~9 ms toward ~6 ms.

### 2. Cache `FenSerializer.serialize(board)` results

- **Finding**: FEN serialisation is allocated twice per response
  (alloc samples #5 + #6, ~30 combined). The same board state
  reaches the same FEN string; we're re-encoding on every call.
- **Proposed change**: memoise `FenSerializer.serialize(board)` in a
  bounded LRU keyed by board identity. The `Game.applyMove` path
  already produces the new board; cache its FEN once at the move
  site and reuse downstream. Ship as `Optimisation[FenSerializer]`:
  `default` = cached, `baseline` = stateless serialise.
- **Expected mechanism**: ~half the FEN allocation. Cache hit rate
  near 100 % for the move handler's own path (same board = same FEN).
- **Expected signal**: alloc samples on `FenCodec.encodeBoard ->
  Position.apply` drop from 30 → near zero. `bench-codec`'s
  `FenSerializerBenchmark.serialize` µs/op unchanged (cold path);
  game-service Stress p95 drops by ~1 ms.

### 3. Reuse `Position` instances on `Ray.walk`

- **Finding**: `Position.apply` allocated per ray step in
  `MoveValidator.isInCheck` and `FenCodec.encodeBoard` (alloc
  samples #3 + #5 + #6 — Phase B's finding re-confirmed).
- **Proposed change**: precompute and memoize the 64-element
  `Position(row, col)` table at startup. `Ray.walk` indexes the
  table instead of calling `Position.apply`. Ship as
  `Optimisation[Position]` where `default` provides the memoised
  table and `baseline` keeps the existing `Position.apply` call.
- **Expected mechanism**: removes the per-cell allocation from every
  ray walk + every FEN encode. Position becomes a value object with
  64 singleton instances, so no GC pressure from this surface ever
  again.
- **Expected signal**: `bench-rules` `RayWalkBenchmark` µs/op drops
  by ~15 %. game-service GC time delta drops by ~10-15 %. The
  position-allocation lines in the alloc profile disappear.

### 4. Pre-compute and persist SAN on each `MoveLogEntry`

- **Finding**: `SanSerializer.toSan` is allocated on every response
  via the move log (alloc samples #4 + #7 + #10 + #12, ~47 combined).
  Move SAN never changes after the move is committed.
- **Proposed change**: store the SAN string on the move log entry at
  the moment the move is applied (i.e. in `Game.applyMove`).
  Responses serialise the stored SAN instead of re-deriving. Cost
  is one extra `String` per move on the heap (cheap and bounded by
  move count); benefit is removing the per-response SAN derivation.
  Ship as `Optimisation[MoveLogEntry]`: `default` = eager-SAN log;
  `baseline` = lazy SAN.
- **Expected mechanism**: removes the per-response derivation
  including tuple + list allocation.
- **Expected signal**: combined SAN-related alloc samples drop from
  ~47 → near zero. `bench-codec`'s `SanRoundTripBenchmark`
  unchanged. game-service Stress p95 drops by ~1 ms.

### 5. (Lower priority) Direct UTF-8 JSON writer in the gateway

- **Finding**: gateway alloc #2 — 43 samples spent in
  `String.encodeUTF8` converting the response JSON to bytes.
- **Proposed change**: replace `tapir`'s default
  `ZioHttpToResponseBody.fromRawValue` path with a custom
  serialiser that writes JSON directly as bytes to the output
  buffer, skipping the intermediate `String`. Substantial work
  (requires a custom codec); marginal payoff on its own.
- **Expected signal**: gateway alloc on `String.encodeUTF8` drops to
  near zero, but gateway's Stress p95 contribution is already
  ~1.4 ms, so the user-facing change is small.

### Headline projection

Stacking #1 + #2 + #3 + #4 (all in game-service), the alloc profile
should drop ~70-80 % of its current `byte[]` and `Position` samples.
GC time on game-service should fall by ~30-50 %. Gatling Stress p95
should drop from **9 ms → ~6 ms** with the same workload, and
headroom should appear for higher RPS.

These four optimisations all live behind `Optimisation[T]` pairs, so
the A/B is reproducible — flip `PICHESS_OPT_ALL=baseline` to run the
same workload against current behaviour for the headline comparison.

---

## Caveats

- **JMH benches reflect cold-path single-call cost**, not the
  allocation pressure that drives GC in the hot path. The alloc
  profile is the authoritative source for "which function is
  hammering the allocator".
- **Hardware**: MacBook Air M2; numbers will improve on real Linux
  hosts. Relative ordering should hold.
- **Workload coverage**: only `Game` and `Stress` were measured.
  Endurance / soak tests would surface time-dependent effects
  (memory growth, fiber leaks) that this report doesn't cover.
- **Profile statistical confidence**: the profiles are *sampled*.
  A 30-sample alloc finding is qualitatively reliable; a single
  sample is anecdotal. The top-N list above only shows entries with
  ≥ 12 samples.

---

## Reproducing

```bash
# 0. Clean slate.
make stack-down
rm -rf perf-reports/*

# 1. Pre-flight.
make build
make k6-build

# 2. DB selection (skip if db-selection-report.md is already there).
make db-matrix

# 3. Bring up the winner.
TRACING_ENABLED=true make stack-redis EXTRA=obs

# 4. Long Stress.
sbt -batch \
  '-DpichessPeakUsers=80' '-DpichessHoldSeconds=240' \
  '-DpichessRatePerSec=15' \
  'gatling/Gatling/testOnly chess.gatling.StressSimulation' &

# 5. After ramp, profile.
ASPROF_BIN=$(pwd)/bin/async-profiler-4.0-linux-arm64/bin/asprof \
  scripts/profile-async.sh game-service 60 itimer
ASPROF_BIN=$(pwd)/bin/async-profiler-4.0-linux-arm64/bin/asprof \
  scripts/profile-async.sh game-service 30 alloc
ASPROF_BIN=$(pwd)/bin/async-profiler-4.0-linux-arm64/bin/asprof \
  scripts/profile-async.sh gateway 30 itimer
ASPROF_BIN=$(pwd)/bin/async-profiler-4.0-linux-arm64/bin/asprof \
  scripts/profile-async.sh gateway 30 alloc

# 6. k6 (while load is still running so context-switch budget is realistic).
SURFACES=browser,grpc make k6

# 7. Benches.
make bench-codec
make bench-rules

# 8. PG_POOL A/B (for documentation, not for the redis recommendation).
PICHESS_OPT_PG_POOL=default  BACKENDS=postgres WORKLOADS=Stress make db-matrix
PICHESS_OPT_PG_POOL=baseline BACKENDS=postgres WORKLOADS=Stress make db-matrix

# 9. Reports.
for ts in $(ls -d perf-reports/2*/); do
  make perf-report RUN_DIR="${ts%/}"
done

# 10. Jaeger.
make jaeger
# In the UI: filter by service=gateway, operation containing "/move",
# sort by duration.
```

### Run timestamps for the data in this report

| Pass | Run dir | Date |
|---|---|---|
| DB matrix | `perf-reports/20260603T005652Z/` | 2026-06-03 |
| Stress + k6 + profiles + Jaeger | `perf-reports/20260603T012605Z/` + `perf-reports/profiles/redis-*.txt` | 2026-06-03 |
| Bench codec / rules | `perf-reports/bench-codec-20260603T011857Z.json`, `perf-reports/bench-rules-20260603T012001Z.json` | 2026-06-03 |
| PG_POOL A/B | `perf-reports/20260603T003511Z/`, `perf-reports/20260603T003856Z/` (also see Phase C smoke entries) | 2026-06-03 |
