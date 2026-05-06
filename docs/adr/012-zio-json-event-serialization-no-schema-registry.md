# 012 — zio-json for Kafka events, no schema registry

## Status
Accepted (Phase 11).

## Context
Kafka events need a wire format. The standard production options are Avro (with a schema registry) or Protobuf. The project already uses zio-json everywhere else for JSON encoding/decoding (Tapir bodies, repository envelopes, web-ui DTOs).

## Decision
Use **zio-json** for the `chess.game-events` topic value. Tagged ADT (`@jsonDiscriminator("type")`) with FEN strings inside. No schema registry.

```scala
@jsonDiscriminator("type")
sealed trait GameDomainEvent:
  def gameId: String
  def resultingFen: String
  def occurredAt: Long

case class MoveMade(gameId: String, resultingFen: String, moveCoord: String, san: String, occurredAt: Long) extends GameDomainEvent
// … 7 more cases
```

Records are keyed by `gameId` (string serde) and produced via `Producer.produce[Any, String, String]`.

## Why not Avro / schema registry
- **Operational cost.** A schema registry is a separate service to run, monitor, version, and back up. We have **one producer** and **one consumer** today.
- **Schema compatibility.** Avro/Protobuf force a compatibility model upfront (BACKWARD/FORWARD/FULL). zio-json's structural decoding lets us evolve fields with `@jsonField` aliases and `Option` defaults — fine for a small ADT.
- **Consistency with the rest of the codebase.** Same library, same derivation macros, same skill set.

## When to revisit
- When there are **3+ heterogeneous consumers** and accidental schema breakage starts costing real time.
- When we add **non-Scala consumers** (e.g. Spark in Phase 13) where re-implementing zio-json codecs would be silly. Spark would consume from Kafka via a Scala client, but if a Python team needs to subscribe, Avro/Protobuf wins on tooling.

## Consequences
- Schema discipline lives in the `events` module's ADT and codecs — code review catches incompatible changes.
- Storage cost is a touch higher than binary formats. Negligible for this workload.
- The consumer rejects unknown event types as decode failures (logged, skipped). Forward compatibility (consumer is older than producer) requires careful field defaults; documented as a constraint when adding new event variants.
