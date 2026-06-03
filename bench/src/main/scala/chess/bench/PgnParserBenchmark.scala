package chess.bench

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

import chess.codec.PgnParser

/** End-to-end PGN parse benchmark over the curated corpus. Each parse
  * involves header extraction, SAN tokenisation, move resolution against
  * the unfolding state, and full [[chess.model.rules.Game.applyMove]] per
  * ply — so this is the broadest single-call benchmark in the suite.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
class PgnParserBenchmark:

  private val corpus: Array[String] =
    BenchFixtures.pgnCorpus.values.toArray

  @Benchmark
  def parseAll: Int =
    var i = 0
    var n = 0
    while i < corpus.length do
      val game = UnsafeRuntime.run(PgnParser.parse(corpus(i)))
      n += game.history.size
      i += 1
    n
