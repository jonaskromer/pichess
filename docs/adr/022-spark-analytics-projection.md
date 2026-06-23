# ADR 022 — Spark analytics projection (Lambda read-side over the event log)

## Status

Accepted

## Context

πChess already publishes every game mutation to the `chess.game-events` Kafka
log (ADR 010), with read-side projections consuming it (ADR 018). The course
unit SA-13 calls for Apache Spark: aggregate application data, first from a
file, then from Spark Streaming over Kafka. We wanted more than the textbook
`groupBy().count()` — analytics that demonstrate stateful streaming, event-time
semantics, and a full reactive loop back into the live app — without adding
operational weight the 4-vCPU / 12-GB HTWG deploy VM can't carry.

Two forces shaped the design:

1. **Spark has no Scala 3 build, and πChess is Scala 3.8.2.** Spark ships only
   for Scala 2.12/2.13, and its runtime encoders use `scala-reflect`, which
   reflects over a *genuine* 2.13 standard library.
2. **The existing `analytics-service` backed onto ClickHouse** — a heavyweight
   columnar OLAP engine whose whole reason for being is query-time aggregation.

## Decision

A new **`spark-analytics`** module: a read-side projection over the same event
log, structured as a **Lambda architecture** — a batch layer over an archived
file dump and a speed layer over the live Kafka topic, both feeding the same
aggregation code. Built with **zio-spark** (ZIO-native Spark) for ecosystem
coherence with the rest of the stack.

### The Scala/JDK compatibility seam (the invisible-but-load-bearing part)

Running real Spark from a Scala 3 repo forced a precise, non-obvious config —
each item below was discovered by an actual failed run, not from docs:

- **zio-spark `0.12.0`, not `0.13.0`.** The `v0.13.0` git tag (Spark 3.5.1) was
  never published to Maven Central; `0.12.0` is the latest released artifact and
  pins **Spark 3.3.1**, **zio 2.0.5**, built on **Scala 3.2.1**.
- **Spark jars via `for3Use2_13`, `% Provided`.** No Scala 3 Spark exists, so
  `spark-core`/`spark-sql` are the 2.13 artifacts pulled with
  `.cross(CrossVersion.for3Use2_13)` (`build.sbt`, `sparkAnalytics` block).
- **The module is pinned to Scala 3.3 (LTS), not the repo's 3.8.2.** Scala 3.7+
  publishes its *own* standalone `scala-library:3.x` (carrying Scala-3 stdlib
  classes like `scala.deriving.Mirror`); Spark's `scala-reflect:2.13.8` cannot
  reflect over it and dies at init with `class Array does not have a member
  apply`. Forcing the library down to a genuine 2.13 instead breaks the Scala 3
  compiler (`ClassNotFoundException: scala.deriving.Mirror$Sum`). The 3.3 LTS
  line still depends on a genuine `scala-library:2.13.16`, giving Spark the
  coherent 2.13 toolchain it needs (`scala-reflect` overridden to match).
- **Consequence — standalone schema.** A 3.3 compiler can't read the 3.8.2 TASTy
  of `events`/`codec`, so the module re-declares a minimal `RawGameEvent`
  zio-json mirror instead of sharing the canonical `GameDomainEvent` ADT. The
  one cost: that mirror must be kept in sync with `chess.events.GameDomainEvent`.
- **Java 17 at run time**, not the repo's Java 23/Docker JRE: Java 21+ removed
  the `DirectByteBuffer(long, int)` constructor Spark's `Platform` reflects into.
  The run config forks with a pinned JDK (`PICHESS_SPARK_JAVA_HOME`), Spark's
  `--add-opens` set, `-Djava.security.manager=allow` (Java 18+ blocks Hadoop's
  `Subject.getSubject`), and the `% Provided` Spark jars put back on the run
  classpath (`build.sbt`, `sparkRunJavaOptions` + the `Compile / run` overrides).

The module is deliberately kept **out of the root aggregate** — Spark drags in a
large jackson/netty closure — and built explicitly with `sparkAnalytics/compile`.

### Beyond textbook: what the jobs actually do

- **Batch from file** (`SparkAnalyticsMain`, `DomainStatsMain`): event/move/game
  counts, top openings, plus domain analytics — a **FEN-derived square-occupancy
  heatmap** (`domain.Fen`), an **opening → outcome** distribution, and
  **think-time** per game (`agg.DomainAggregations`).
- **Stateful streaming** (`GameSessionStreamMain`): per-game **sessionization**
  via `flatMapGroupsWithState` — folds each game's event stream into running
  state and emits one `GameSummary` (moves, duration, opening, result,
  think-time) on completion (`session.SessionPipeline`/`GameSessions`).
- **Event-time windowing** (`WindowedStreamMain`): tumbling windows over
  `occurredAt` with a **watermark** for late data (`agg.Aggregations`).

### The reactive loop-back, and dropping ClickHouse

Once Spark is the aggregation engine, ClickHouse's query-time-OLAP role is
subsumed: the serving layer only ever holds small, pre-computed results. So we
**drop ClickHouse** and close the loop on infrastructure we already run:

```
chess.game-events ─▶ spark-analytics (sessionize)
                  ─▶ chess.analytics topic            (AnalyticsSinkMain)
                  ─▶ gateway zio-kafka relay ─▶ Hub    (controller.AnalyticsRelay)
                  ─▶ SSE  GET /api/analytics/events     (controller.AnalyticsRoutes)
                  ─▶ web-ui Laminar "Live analytics" panel
```

The shared wire contract is `chess.api.AnalyticsSummaryDto` (cross-compiled,
round-trip tested). `analytics-service` is **repointed off ClickHouse**: it now
consumes `chess.analytics` and folds each summary into **in-memory aggregate
state** (`AnalyticsState`/`LiveAnalyticsService`) behind the same REST endpoints
(count / avg-length / top-openings — all derivable from `GameSummary`). There is
**no database**: the durable store is the Kafka topic itself, replayed from
earliest on restart to rebuild the aggregates. The `clickhouse` service, its
JDBC layer, schema, and the old `chess.game-events` projection are deleted; the
`clickhouse` container is removed from `docker-compose`. The Spark **batch**
layer additionally persists authoritative views to **Parquet** for durable
historical serving and batch⊕speed reconciliation (`BatchServingMain`). The
gateway relay and the analytics consumer both start only when
`KAFKA_BOOTSTRAP_SERVERS` is set, so Kafka-less setups still run.

## Consequences

- **Gains:** a genuinely advanced Spark integration (stateful + event-time +
  reactive loop-back) that removes a heavyweight container; the architecture
  story is "we moved aggregation from query-time (ClickHouse) to stream-time
  (Spark) and serve pre-computed views from the bus and cache we already had."
- **Costs / trade-offs:**
  - The module is a **Scala 3.3 + Java 17 island** inside a Scala 3.8.2 / Java 23
    repo — justified by Spark's reflection requirements, but a real seam.
  - **Schema duplication:** `RawGameEvent` and `AnalyticsSummaryDto` mirror
    `GameDomainEvent` / `GameSummary` and must be kept in sync (the alternative,
    sharing across the 3.3↔3.8 TASTy gap, is impossible).
  - **Lost:** ad-hoc, slice-by-any-dimension OLAP over raw events at query time.
    Spark batch jobs can answer any new question over the archive, just not in
    sub-second interactive SQL — acceptable for a fixed dashboard.
  - **In-memory serving** means analytics-service rebuilds state by replaying
    `chess.analytics` on restart; fine while the topic's retention covers all
    completed games, but it trades durable random-access for simplicity. A
    Redis/Parquet-backed serving impl can slot behind the same trait later.
  - Spark `% Provided` jars mean a real run needs the run-config classpath +
    JDK pinning above; this is not the same artifact as the CI build.
