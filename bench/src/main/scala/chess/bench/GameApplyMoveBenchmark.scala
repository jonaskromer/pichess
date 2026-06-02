package chess.bench

import chess.model.rules.Game
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit

/** Full [[Game.applyMove]] cycle: validation, board mutation, status
  * detection (including the implicit `hasLegalMove` scan used for
  * checkmate/stalemate resolution). One iteration plays five canonical
  * opening moves so the measured time covers a range of piece types.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
class GameApplyMoveBenchmark:

  private val state = BenchFixtures.startingState
  private val move  = BenchFixtures.openingMoves.head // e2-e4

  @Benchmark
  def singleMove: Int =
    UnsafeRuntime.run(Game.applyMove(state, move)).board.size

  @Benchmark
  def fivePlies: Int =
    val moves = BenchFixtures.openingMoves
    var st = state
    var i = 0
    while i < moves.length do
      st = UnsafeRuntime.run(Game.applyMove(st, moves(i)))
      i += 1
    st.board.size
