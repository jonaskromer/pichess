# 010 — Kafka as event log (one topic, no command bus)

## Status
Accepted (Phase 11).

## Context
After splitting `app` into separate `gateway` and `game-service` services, we needed a way to feed the repository asynchronously and to leave room for future analytics/projection consumers. The lecture (SA-11) requires a Kafka producer + consumer wired to the data stream.

## Decision
- Use Kafka as an **event log**, not a command bus. `game-service` is the sole producer; `repository` is the consumer (write side). Future projections can subscribe without changing producers.
- **One topic** (`chess.game-events`), partition key = `gameId`. Per-game ordering is what consumers need; splitting events across topics by type would force consumers to merge streams to recover order.
- Run **KRaft mode** (no Zookeeper). One fewer container, modern Kafka default.
- Publish synchronously from the producer's effect chain — the MakeMove rpc returns to the gateway only after the produce future resolves with `acks=all` + idempotence on. No client is told "your move succeeded" until Kafka has it.

## Why not a command bus
Routing every gateway → game-service call through Kafka would add ms-to-tens-of-ms of latency (network + flush) on the critical path. Synchronous gRPC for command + async Kafka for fan-out gives us both responsiveness and scalability.

## Why SSE is fed from gRPC, not from Kafka
The gateway's `/api/events` SSE endpoint is implemented via the gameService `SubscribeGame` server-streaming rpc, not by consuming Kafka in the gateway. Reasons:
1. **Snapshot semantics.** game-service has the current state in memory and emits "current + subsequent" trivially. A Kafka-backed SSE source would need a compacted topic or a snapshot-load step.
2. **Less gateway state.** A Kafka-consuming gateway would have to fan out per-`gameId` across all browser sessions; the gRPC stream is one line.

Tradeoff: a future "see live game N from any gateway replica" feature would benefit from a Kafka-backed gateway. Acceptable for now; the public SSE contract is unchanged either way.

## Consequences
- **Repository writes are eventually consistent** w.r.t. game-service state. Acceptable — the repository is a read-side projection; the authoritative state lives in game-service.
- **gameService restart loses state.** Replay-from-Kafka-on-startup is the documented next iteration.
- The strangler step (legacy HTTP PUT retained briefly alongside the consumer) made the migration safe — both paths are idempotent.
