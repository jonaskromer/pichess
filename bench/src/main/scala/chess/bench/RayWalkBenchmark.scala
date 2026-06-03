package chess.bench

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

import chess.model.board.Position
import chess.model.piece.PieceType
import chess.model.rules.Ray

/** Microbench for [[Ray.walk]] — the per-direction ray-casting primitive
  * used by every sliding-piece move generator. Iterates over the queen's
  * eight rays from a central square on a starting-position board.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
class RayWalkBenchmark:

  private val board    = BenchFixtures.startingState.board
  private val midBoard = BenchFixtures.midGameState.board
  private val origin   = Position('e', 4)
  private val queenRays = Ray.table(PieceType.Queen)

  @Benchmark
  def queenRaysFromCenter: Int =
    var i  = 0
    var n  = 0
    while i < queenRays.length do
      n += Ray.walk(board, origin, queenRays(i)).size
      i += 1
    n

  @Benchmark
  def queenRaysFromCenterMid: Int =
    var i  = 0
    var n  = 0
    while i < queenRays.length do
      n += Ray.walk(midBoard, origin, queenRays(i)).size
      i += 1
    n
