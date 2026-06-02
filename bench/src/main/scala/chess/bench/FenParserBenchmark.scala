package chess.bench

import chess.codec.{FenParserCombinator, FenParserFastParse, FenParserRegex}
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit

/** A/B/C comparison of the three FEN parser implementations on an
  * identical corpus. The bench answers a real question for the codec
  * module — which parser is fastest for the same workload — and is the
  * canonical demo of the JMH harness in the report.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
class FenParserBenchmark:

  private val corpus: Array[String] = BenchFixtures.fenCorpus.toArray

  @Benchmark
  def regex: Int =
    var i = 0
    var n = 0
    while i < corpus.length do
      val s = UnsafeRuntime.run(FenParserRegex.parse(corpus(i)))
      n += s.board.size
      i += 1
    n

  @Benchmark
  def combinator: Int =
    var i = 0
    var n = 0
    while i < corpus.length do
      val s = UnsafeRuntime.run(FenParserCombinator.parse(corpus(i)))
      n += s.board.size
      i += 1
    n

  @Benchmark
  def fastparse: Int =
    var i = 0
    var n = 0
    while i < corpus.length do
      val s = UnsafeRuntime.run(FenParserFastParse.parse(corpus(i)))
      n += s.board.size
      i += 1
    n
