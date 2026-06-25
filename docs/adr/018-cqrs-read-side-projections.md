# ADR 018 — CQRS read-side: Kafka projections into query-shaped stores

## Status

Accepted

> **Update (analytics half superseded by [ADR 022](022-spark-analytics-projection.md)):**
> the **ClickHouse** `move_events` projection described below was removed. The
> analytics-service no longer writes ClickHouse — `KafkaAnalyticsConsumer` now folds
> events into in-process Prometheus metrics (`AnalyticsMetrics`), and the Spark
> speed-layer (ADR 022) owns durable analytics. The `AnalyticsSchema.scala` /
> `AnalyticsProjection.scala` files cited under "Decision" no longer exist. The
> **Neo4j opening-tree** projection (the other half of this ADR) is unchanged and
> still accurate.

## Context

The single `chess.game-events` topic ([ADR 010](010-kafka-as-event-log.md)) was
built to "leave room for future analytics/projection consumers" — but ADR 010
only *foreshadows* them (`:10`). Two genuinely different read questions then
arrived: "what moves follow this opening position?" (a graph traversal) and
"aggregate move statistics across all games" (an OLAP scan). Neither fits the
write-side store well.

## Decision

Two **independent projection services**, each its own Kafka consumer group,
project the one event topic into a store **shaped to its query**:

- **opening-service → Neo4j graph.** `KafkaOpeningConsumer` (group
  `pichess-opening`, `OpeningMain.scala:19`) subscribes `Topics.GameEvents`
  (`:50`); `Neo4jOpeningTree` MERGEs `(b:Position)-[:MOVE {san}]->(a:Position)`
  (`Neo4jOpeningTree.scala:15-17`) — a position-to-position graph for opening
  traversal.
- **analytics-service → ClickHouse OLAP.** `KafkaAnalyticsConsumer` (group
  `pichess-analytics`, `AnalyticsMain.scala:21`) projects the same topic into a
  flat columnar `move_events` MergeTree table (`AnalyticsSchema.scala:27-35`,
  `INSERT` at `AnalyticsProjection.scala:37`) — one row per event for column
  scans.

Both are **read-only** (no producer back to the topic or game-service),
**optional** (compose `profiles: ["opening"]` / `["analytics"]`; absent from
every k8s overlay), and read the **shared** `Topics.GameEvents` constant
(`events/.../Topics.scala:11`). Distinct consumer groups mean each projects the
full stream independently and either can be rebuilt without touching the other.

The non-obvious part is not "consume Kafka" — it is **matching store shape to
query shape** (graph vs columnar) and isolating each as its own consumer group.

## Consequences

**Benefits:**
- Each read model is queried in its native idiom (Cypher traversal, columnar
  scan) instead of contorting the write store.
- Projections are bolt-on: independent groups, optional profiles, zero impact on
  the core game path. A non-Scala team could add a third group the same way.

**Trade-offs:**
- **Non-idempotent under replay.** Both consumers reset
  `auto.offset.reset = earliest` (`KafkaOpeningConsumer.scala:41`,
  `KafkaAnalyticsConsumer.scala:36`). ClickHouse uses a plain `INSERT` into a
  `MergeTree` (not `ReplacingMergeTree`), so a replay duplicates rows; Neo4j's
  `MERGE` avoids duplicate nodes/edges but `ON MATCH SET m.count = m.count + 1`
  re-inflates the edge tally. At-least-once is accepted for these demo read
  models; exactly-once would need `ReplacingMergeTree` / idempotent counts.
- Two more datastores (Neo4j, ClickHouse) to run when the profiles are enabled.
