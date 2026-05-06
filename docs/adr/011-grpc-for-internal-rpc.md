# 011 — gRPC for internal RPC, Tapir REST kept on the public surface

## Status
Accepted (Phase 11).

## Context
After splitting `app`, the gateway needs to call game-service synchronously for every command (MakeMove, Undo, GetState, …). The lecture frames this as inter-service IPC; we needed a typed contract that both sides compile against.

## Decision
- Use **zio-grpc** (`com.thesamet.scalapb.zio-grpc`) for all internal service-to-service RPC. Effects are `IO[Status, A]` natively, no `Future`/`Task` adapters. Generated stubs return `IO[StatusException, StateReply]`.
- Single proto file: `proto/src/main/protobuf/pichess/game_service.proto`. The `proto` module is a shared library imported by gateway (client) and game-service (server).
- **Wire format:** `GameState` is carried as a **FEN string** (and `Move` as origin/destination squares + optional promotion). We do **not** model `Board` / `Piece` in protobuf — that would duplicate the `codec` module's job. FEN-on-the-wire matches the existing convention used by `repositoryApi`.
- **Keep Tapir REST on the public surface.** The gateway's `/api/*` endpoints stay Tapir-described in the cross-compiled `api` module, which is also what the Scala.js web-ui consumes (and what the future TUI REST client will use). Replacing this with raw zio-http routing would break the shared-contract story; replacing with gRPC-on-the-public-surface would lose Gatling-friendly HTTP load tests.

## Server-streaming for SSE
The proto exposes `rpc SubscribeGame(GameIdRequest) returns (stream StateReply)`. The gateway's SSE endpoint is a thin wrapper around this — no Kafka in the SSE path (see [ADR 010](010-kafka-as-event-log.md)).

## Tapir is not ZIO-native — why keep it
The existing `api` module is a cross-compiled Tapir contract used by both gateway server and Scala.js client. Replacing Tapir with raw zio-http routing would either (a) re-implement the contract in two places or (b) lose the typed sttp client codegen used by the web UI. Tapir integrates with zio-http via `tapir-zio-http-server`, so server effects stay `RIO`. Net: Tapir stays on the public surface; everything *internal* is zio-grpc.

## Consequences
- One more dep set: sbt-protoc + zio-grpc-codegen + grpc-netty + scalapb-runtime-grpc. The `proto` module has `coverageEnabled := false` because the generated code shouldn't count.
- Any change to the contract requires a `sbt proto/compile` pass before downstream services compile.
- Errors are mapped at the rpc boundary: `GameError` → `Status.{INVALID_ARGUMENT, NOT_FOUND, INTERNAL}` in `GrpcServer.toStatusException`. The internal error channel stays typed (`IO[GameError, A]`) up to the boundary.
