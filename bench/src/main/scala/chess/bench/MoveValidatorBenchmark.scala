package chess.bench

import chess.model.piece.Color
import chess.model.rules.MoveValidator
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit

/** Microbenchmarks for the move-legality predicates. `isInCheck` is the
  * inner-loop bottleneck of `hasLegalMove`, which is itself called once
  * per ply by [[chess.model.rules.Game.applyMove]] for
  * checkmate/stalemate detection.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
class MoveValidatorBenchmark:

  private val startingState = BenchFixtures.startingState
  private val midGameState  = BenchFixtures.midGameState

  @Benchmark
  def isInCheckStart: Boolean =
    MoveValidator.isInCheck(startingState.board, Color.White)

  @Benchmark
  def isInCheckMidGame: Boolean =
    MoveValidator.isInCheck(midGameState.board, Color.White)

  @Benchmark
  def hasLegalMoveStart: Boolean =
    UnsafeRuntime.run(MoveValidator.hasLegalMove(startingState))

  @Benchmark
  def hasLegalMoveMidGame: Boolean =
    UnsafeRuntime.run(MoveValidator.hasLegalMove(midGameState))
