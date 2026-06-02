package chess.bench

import chess.model.rules.Zobrist
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit

/** Pure-function [[Zobrist.hash]] microbench. The hash is called every
  * time `GameSnapshot.recordMove` increments the position-count map; under
  * sustained gameplay it's on the hot path for repetition detection.
  *
  * Output in NANOSECONDS — this is a tight inner loop and rounding to
  * microseconds would lose resolution.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
class ZobristHashBenchmark:

  private val starting = BenchFixtures.startingState
  private val mid      = BenchFixtures.midGameState

  @Benchmark
  def hashStart: Long = Zobrist.hash(starting)

  @Benchmark
  def hashMidGame: Long = Zobrist.hash(mid)
