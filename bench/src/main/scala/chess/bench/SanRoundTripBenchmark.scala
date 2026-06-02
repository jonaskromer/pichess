package chess.bench

import chess.model.rules.Game
import chess.notation.{MoveParser, SanSerializer}
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit

/** Roundtrip benchmark: parse a SAN string against a state, apply the
  * resulting move, serialize it back to SAN. Exercises both notation
  * codecs over a realistic 16-move sequence.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
class SanRoundTripBenchmark:

  private val sequence = BenchFixtures.sanSequences("ruyLopez").toArray
  private val start    = BenchFixtures.startingState

  @Benchmark
  def parseApplySerializeAll: Int =
    var st     = start
    var totalChars = 0
    var i      = 0
    while i < sequence.length do
      val move = UnsafeRuntime.run(MoveParser.parse(sequence(i), st))
      val san  = UnsafeRuntime.run(SanSerializer.toSan(move, st))
      st = UnsafeRuntime.run(Game.applyMove(st, move))
      totalChars += san.length
      i += 1
    totalChars
