package chess.bench

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

import chess.codec.{FenParserRegex, FenSerializer}
import chess.model.board.GameState

/** Roundtrip + standalone serialize benchmarks. Tracks regressions in the
  * board-to-FEN path that Kafka events + repository writes pay on every
  * state transition.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
class FenSerializerBenchmark:

  private val states: Array[GameState] =
    BenchFixtures.fenCorpus
      .map(s => UnsafeRuntime.run(FenParserRegex.parse(s)))
      .toArray

  @Benchmark
  def serialize: Int =
    var i = 0
    var n = 0
    while i < states.length do
      n += FenSerializer.serialize(states(i)).length
      i += 1
    n

  @Benchmark
  def positionKey: Int =
    var i = 0
    var n = 0
    while i < states.length do
      n += FenSerializer.positionKey(states(i)).length
      i += 1
    n
