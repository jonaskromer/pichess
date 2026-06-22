# ADR 017 — boopickle as the binary wire codec for gRPC DTO payloads

## Status

Accepted — supersedes the "wire format" description in
[ADR 011](011-grpc-for-internal-rpc.md).

## Context

`game-service` returns board state to the gateway on every move over gRPC
([ADR 011](011-grpc-for-internal-rpc.md)). The original contract carried a FEN
string and re-modelled a move as origin/destination squares. Two problems: FEN
loses information the UI wants (annotations, exact SAN) and forces re-deriving
it; and modelling `Board`/`Piece` fully in protobuf would duplicate the `codec`
module's job. We benchmarked candidate codecs for the DTO round-trip
(`bench/.../BoardStateDtoBenchmark.scala`, `docs/performance.md`).

## Decision

Carry the DTOs as **boopickle-encoded `bytes`** on the gRPC payload.

- `BoardStateDto` and `AnnotationsDto` (`api/.../BoardStateDto.scala`) each get a
  boopickle `Pickler` plus `encodeBytes`/`decodeBytes` (`:85-95`, `:245-252`;
  `import boopickle.Default.*` at `:5`).
- `StateReply` carries `bytes board_state = 2` and `bytes annotations = 5`;
  `fen = 4` is **demoted to a fallback/debug field**
  (`proto/.../game_service.proto:95-101`). `GrpcMappers.encodeBoardState`
  (`game-service/.../GrpcMappers.scala:61`) produces the bytes; the gateway's
  `WebController` decodes them.
- A move crosses as a **single notation string** (`MoveRequest.raw` —
  coordinate/castling/SAN, `:66-69`), not square coordinates.
- **Not** modelled in protobuf, **not** zio-schema-protobuf, **not** JSON.

Why boopickle: `docs/performance.md`'s codec table (`:692-697`) measures the
round-trip at ≈ 12 µs for boopickle vs ≈ 416 µs for zio-schema-protobuf —
**33× slower** — with zio-json ≈ 18 µs in between. zio-schema-protobuf was
dropped from the live benchmark for exactly that reason, and an initial
protobuf-modelled migration regressed end-to-end p99 ≈ 7×.

This is **distinct from event serialization**: Kafka events use zio-json
([ADR 012](012-zio-json-event-serialization-no-schema-registry.md)); boopickle is
confined to the gRPC DTO payload.

## Consequences

**Benefits:**
- The hot path (a payload on every move) uses the fastest codec we measured, and
  the wire shape *is* the domain DTO shape — no FEN re-derivation on the gateway.
- The proto stays tiny: one `bytes` field, no `Board`/`Piece` duplication of the
  `codec` module.

**Trade-offs:**
- The `bytes` payload is opaque to non-Scala / non-boopickle gRPC clients — both
  ends must share the Scala `BoardStateDto`. Accepted: both ends are ours.
- A boopickle schema mismatch fails at decode time, not at proto-compile time;
  `fen` is retained as a human-readable fallback partly to cushion this.
