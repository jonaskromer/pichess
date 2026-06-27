package chess.bench.wire

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*
import zio.json.*

import chess.events.GameDomainEvent

/** Wire-format cost of the **Kafka event** payload (`GameDomainEvent` JSON, the
  * `chess.game-events` format). Every move publishes one, and the analytics /
  * archiver / opening consumers each decode every event — so at high game
  * throughput this encode/decode is on the hot path for the whole event-driven
  * tail. `@jsonDiscriminator("type")` adds a small tag; this measures the real
  * cost for the two dominant variants (a move and a terminal).
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
class EventCodecBenchmark:

  private val move: GameDomainEvent =
    GameDomainEvent.MoveMade(
      gameId = "abcd-1234-ef56-7890",
      resultingFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
      moveCoord = "e2 e4",
      san = "e4",
      occurredAt = 1719300000000L
    )

  private val ended: GameDomainEvent =
    GameDomainEvent.GameEnded(
      gameId = "abcd-1234-ef56-7890",
      resultingFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 1",
      status = "Checkmate(White)",
      occurredAt = 1719300050000L
    )

  private val moveJson: String = move.toJson
  private val endedJson: String = ended.toJson

  @Benchmark def encode_move(): String = move.toJson
  @Benchmark def encode_ended(): String = ended.toJson

  @Benchmark def decode_move(): GameDomainEvent =
    moveJson.fromJson[GameDomainEvent].fold(e => throw new RuntimeException(e), identity)

  @Benchmark def decode_ended(): GameDomainEvent =
    endedJson.fromJson[GameDomainEvent].fold(e => throw new RuntimeException(e), identity)
