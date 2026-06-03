package chess.bench

import chess.codec.FenParserRegex
import chess.model.board.{GameState, Position}
import chess.model.piece.Color
import chess.model.rules.MoveValidator
import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit

/** Microbenchmarks for the move-legality predicates.
  *
  * `isInCheck` is the inner-loop bottleneck of `hasLegalMove` (which
  * gates checkmate/stalemate detection in `Game.applyMove`) AND of
  * `legalMovesFrom` (called per piece during the gateway's annotation
  * rebuild — see `chess.controller.WebController.computeAnnotations`).
  * Under realistic UI workloads (legal-moves query before each move),
  * the annotation rebuild path dominates gateway CPU. These benches
  * separate the per-call cost of each predicate so optimisation work
  * (king-position cache, attack-table maintenance, bitboards, …) can
  * be evaluated against each candidate.
  *
  * Three position fixtures so we cover the geometric spread:
  *   - starting position: full board, no captures, dense
  *   - mid-game (Ruy Lopez 8-ply): minor pieces developed, both sides castled
  *   - Kiwipete: complex middlegame, lots of pieces attacking lots of squares
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
  // Kiwipete — high-attack-density mid-game with castling rights, en
  // passant, and multiple attackers per square. Standard perft fixture.
  private val kiwiState: GameState =
    UnsafeRuntime.run(
      FenParserRegex.parse(
        "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
      )
    )

  // The squares the king occupies in each fixture (precomputed so the
  // `isSquareAttacked` bench doesn't include the king-find cost — that
  // separates the two halves of `isInCheck`).
  private val whiteKingStart: Position = Position('e', 1)
  private val whiteKingMid:   Position = Position('g', 1) // castled
  private val whiteKingKiwi:  Position = Position('e', 1)

  // -- isInCheck (king-find + isSquareAttacked combined) ----------------

  @Benchmark
  def isInCheckStart: Boolean =
    MoveValidator.isInCheck(startingState.board, Color.White)

  @Benchmark
  def isInCheckMidGame: Boolean =
    MoveValidator.isInCheck(midGameState.board, Color.White)

  @Benchmark
  def isInCheckKiwiPete: Boolean =
    MoveValidator.isInCheck(kiwiState.board, Color.White)

  // -- isSquareAttacked (king position passed in, isolates the attacker scan)

  @Benchmark
  def isSquareAttackedStart: Boolean =
    MoveValidator.isSquareAttacked(startingState.board, whiteKingStart, Color.White)

  @Benchmark
  def isSquareAttackedMidGame: Boolean =
    MoveValidator.isSquareAttacked(midGameState.board, whiteKingMid, Color.White)

  @Benchmark
  def isSquareAttackedKiwiPete: Boolean =
    MoveValidator.isSquareAttacked(kiwiState.board, whiteKingKiwi, Color.White)

  // -- attackersOf (full list, not just a boolean — gateway annotation use)

  @Benchmark
  def attackersOfMidGame: List[Position] =
    MoveValidator.attackersOf(midGameState.board, whiteKingMid, Color.Black)

  @Benchmark
  def attackersOfKiwiPete: List[Position] =
    MoveValidator.attackersOf(kiwiState.board, whiteKingKiwi, Color.Black)

  // -- legalMovesFrom (per-piece, per-square; the body of computeAnnotations)

  @Benchmark
  def legalMovesFromKnightStart: List[Position] =
    UnsafeRuntime.run(MoveValidator.legalMovesFrom(startingState, Position('g', 1)))

  @Benchmark
  def legalMovesFromQueenMidGame: List[Position] =
    UnsafeRuntime.run(MoveValidator.legalMovesFrom(midGameState, Position('d', 1)))

  @Benchmark
  def legalMovesFromKingKiwiPete: List[Position] =
    UnsafeRuntime.run(MoveValidator.legalMovesFrom(kiwiState, whiteKingKiwi))

  // -- hasLegalMove (whole-board scan; checkmate/stalemate detector)

  @Benchmark
  def hasLegalMoveStart: Boolean =
    UnsafeRuntime.run(MoveValidator.hasLegalMove(startingState))

  @Benchmark
  def hasLegalMoveMidGame: Boolean =
    UnsafeRuntime.run(MoveValidator.hasLegalMove(midGameState))

  @Benchmark
  def hasLegalMoveKiwiPete: Boolean =
    UnsafeRuntime.run(MoveValidator.hasLegalMove(kiwiState))
