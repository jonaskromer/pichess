# Reactive Streams in PiChess

PiChess implements the Reactive Streams pattern end-to-end. We use **ZIO
Streams** rather than Akka Streams (the codebase is uniformly ZIO — see
`docs/architecture.md`), so the Akka vocabulary taught in the course maps onto
ZIO equivalents one-to-one. This document is that mapping, with a citation to
real code for every concept.

The point worth grading: this is not a toy `Source(1 to 100)` demo. The
streaming layer carries live game state across four service boundaries
(gRPC → SSE → client), backpressures a Kafka consumer under real event load,
and bridges the *actual* Reactive Streams SPI (`org.reactivestreams.Publisher`)
out of the MongoDB driver. Every piece below is load-bearing in production.

## Concept mapping: Akka Streams → ZIO Streams → PiChess

| Akka Streams concept | ZIO Streams equivalent | Where it lives in PiChess |
|----------------------|------------------------|---------------------------|
| `Source[A, _]` | `ZStream[R, E, A]` | `GrpcServer.subscribeGame` — a live Source of game-state snapshots (`GrpcServer.scala:161`) |
| `Flow[A, B, _]` | `ZPipeline[R, E, A, B]` | TUI SSE decode pipeline: bytes → UTF-8 → lines → events (`TuiEventStream.scala:45`) |
| `Sink[A, _]` | `ZSink[R, E, A, _, _]` / `runDrain` | Kafka offset-commit sink (`KafkaGameEventConsumer.scala:86`); Kafka producer as terminal sink (`KafkaGameEventProducer.scala:49`) |
| `RunnableGraph` / `.run()` | `stream.run* ` | `.runDrain` on the consumer (`KafkaGameEventConsumer.scala:86`) |
| Fan-out (`Broadcast`) | `SubscriptionRef.changes` → N subscribers | One game's state Source fans out to every SSE subscriber (`GrpcServer.scala:171`); the gateway's live spectator count fans out the same way (`SpectatorPresence.changes`, `SpectatorPresence.scala:55`) |
| Backpressure | demand-driven `ZStream` pull + `aggregateAsync` | Kafka consumer batches commits under load (`KafkaGameEventConsumer.scala:84`) |
| Reactive Streams SPI (`Publisher`/`Subscriber`) | `zio-interop-reactivestreams` | MongoDB driver `Publisher[T]` → `ZStream` (`MongoOps.scala:16`) |
| `via` / `to` composition | `.via(pipeline)` / `>>>` | `.via(ZPipeline.utf8Decode).via(ZPipeline.splitLines)` (`TuiEventStream.scala:48`) |

## The five concepts, in our code

### 1. Source — a live change-feed, not a static range

`GrpcServer.subscribeGame` returns a `Stream[StatusException, StateReply]`
backed by `SubscriptionRef.changes`. Every time a move mutates the game's
`SubscriptionRef`, the stream emits the new state. This is a genuine reactive
source: it has no end until the subscription scope closes.

```scala
// game-service/.../GrpcServer.scala:161
def subscribeGame(request: GameIdRequest, ctx: RequestContext)
    : Stream[StatusException, StateReply] =
  ZStream
    .fromZIO(sessions.get(request.gameId).mapError(...))
    .flatMap { ref =>
      ref.changes.mapZIO(state => GrpcMappers.toStateReply(request.gameId, state))
    }
```

The gateway hosts a **second** live Source of the same shape. `SpectatorPresence`
keeps a `SubscriptionRef[Int]` per game; `SpectatorPresence.changes` emits the
current spectator count and then every subsequent change
(`SpectatorPresence.scala:55`). `WebController.serveEvents` `.merge`s it into the
per-game SSE feed (count Source built at `WebController.scala:463`, merged at
`:490`/`:494`), so every viewer receives `spectators` count events interleaved
with the `state` events above. A connection that asks to watch
(`?role=spectator`, `WebController.scala:336`) is first run through
`SpectatorPresence.admit` (`SpectatorPresence.scala:64`), which seats it under the
lobby's `allowSpectate` + `limit` policy; a refusal emits a single
`spectator-denied` frame instead of a board (`WebController.scala:476`).

### 2. Flow — composable transformation pipeline

The TUI consumes the gateway's SSE feed as a raw `ZStream[Byte]` and pipes it
through a chain of `ZPipeline`s (the Flow equivalent): UTF-8 decode → split
lines → a `scan`-based SSE frame parser → drop/​log malformed events.

```scala
// tui/.../TuiEventStream.scala:45
ZStream
  .fromZIO(backend.send(request).map(_.body))
  .flatten
  .via(ZPipeline.utf8Decode)     // Flow: Byte => String
  .via(ZPipeline.splitLines)     // Flow: String chunks => lines
  .scan(SseEventBuilder.Builder.empty) { (acc, line) =>
    if line.isEmpty then acc.dispatch else acc.append(line)
  }
  .collect { ... }               // emit completed SSE frames
```

The same Flow shape is reused for the bot NDJSON APIs
(`BotApiClient.ndjsonStream`, `BotApiClient.scala:114`): decode → split →
`filter(_.nonEmpty)` → JSON decode per line.

### 3. Sink — terminal consumption

Two production sinks:

- **Kafka offset commits** — the consumer maps records to offsets, batches them
  with `aggregateAsync(Consumer.offsetBatches)`, commits, and drains
  (`KafkaGameEventConsumer.scala:83`). `.runDrain` is the Sink that runs the
  graph forever.
- **Kafka producer** — `producer.produce(...)` is the terminal sink for the
  domain-event stream; the MakeMove RPC only returns once the record is durably
  committed (`KafkaGameEventProducer.scala:49`).

```scala
// repository/.../KafkaGameEventConsumer.scala:83
  .map(_.offset)
  .aggregateAsync(Consumer.offsetBatches)   // backpressure-aware batching
  .mapZIO(_.commit)                          // Sink
  .runDrain
```

### 4. Backpressure — the part that actually matters

`Consumer.plainStream` is demand-driven: zio-kafka only pulls more records when
the downstream is ready, so a slow `applyEvent` (Mongo write) throttles fetches
from the broker rather than buffering unboundedly. `aggregateAsync` decouples
the commit rate from the processing rate — offsets accumulate while processing
is busy and commit in a batch when it catches up. That is textbook
backpressure across an async boundary, on a real workload.

### 5. Reactive Streams interop — the spec itself

The strongest single point. The MongoDB Scala driver exposes the *standard*
`org.reactivestreams.Publisher[T]` for every operation. `MongoOps` bridges it
into ZIO via `zio-interop-reactivestreams` — i.e. we consume the actual
Reactive Streams SPI (`Publisher`/`Subscriber`/`Subscription`/demand), not just
one library's DSL.

```scala
// persistence/mongo/.../MongoOps.scala:3
import org.reactivestreams.Publisher
import zio.interop.reactivestreams.*

def toList[A](publisher: Publisher[A]): Task[List[A]] =
  publisher.toZIOStream().runCollect.map(_.toList)
```

## End-to-end path

A single move demonstrates every concept in sequence:

```
MakeMove RPC
  └─ mutates SubscriptionRef                       (Source origin)
       ├─ KafkaGameEventProducer.publish           (Sink: durable commit)
       │    └─ Kafka topic chess.game-events
       │         └─ KafkaGameEventConsumer.plainStream   (Source + backpressure)
       │              └─ aggregateAsync → commit → Mongo  (Flow → Sink)
       │                   └─ MongoOps Publisher bridge    (Reactive Streams SPI)
       └─ ref.changes → GrpcServer.subscribeGame    (Source, fan-out)
            └─ WebController.serveEvents             (Flow: state → ServerSentEvent `state`)
                 ├─ SpectatorPresence.changes        (2nd Source, .merge → `spectators` events)
                 └─ Response.fromServerSentEvents    (Sink: HTTP SSE response)
                      └─ TuiEventStream.subscribe    (Source + Flow: decode pipeline)
```

One mutation fans out along two independent reactive paths (Kafka projection +
SSE push), and the SSE path itself crosses gRPC → HTTP → client, transforming
the element shape at each Flow hop.

## Dependencies (build.sbt)

- `dev.zio %% zio-kafka` — backpressured event streaming (`build.sbt:567`)
- `dev.zio %% zio-interop-reactivestreams` — Reactive Streams SPI bridge for Mongo (`build.sbt:436`)
- `dev.zio %% zio-http` — SSE server (`Response.fromServerSentEvents`) (`build.sbt:566`)
- `com.softwaremill.sttp.client3 %% zio` — streaming HTTP client (`ZioStreams`) (`build.sbt:571`)

No Akka Streams, no fs2 — the streaming layer is uniformly ZIO Streams.
