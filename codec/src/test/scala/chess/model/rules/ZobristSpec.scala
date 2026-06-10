package chess.model.rules

import zio.*
import zio.test.*

import chess.codec.{FenSerializer, PositionIdentityBehaviors}
import chess.model.GameError
import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import chess.notation.MoveParser

/** Contract tests for [[Zobrist.hash]] as a position-identity function.
  *
  * The shared behaviors (sensitivity, symmetry) are defined in
  * [[PositionIdentityBehaviors]] and also instantiated by
  * [[chess.codec.PositionKeySensitivitySpec]] against FEN-based positionKey.
  *
  * Zobrist-specific tests added here:
  *   - '''Distinctness sweep''': replay the curated game sequences used
  *     elsewhere in the suite, collect every state reached, and assert that the
  *     Zobrist hash partitions them identically to the FEN position-key. Any
  *     divergence is a Zobrist correctness bug.
  *   - '''Stability''': the hash of the initial position is non-zero and
  *     consistent across calls — a minimal regression guard against a change to
  *     the seed or the tables accidentally collapsing to zero.
  */
object ZobristSpec extends ZIOSpecDefault:

  /** Replay curated sequences and collect all reached states. Every distinct
    * position across these sequences contributes to the distinctness sweep.
    */
  private def walkSequence(
      moves: List[String]
  ): IO[GameError, List[GameState]] =
    ZIO
      .foldLeft(moves)((GameState.initial, List(GameState.initial))) {
        case ((state, acc), san) =>
          for
            move <- MoveParser.parse(san, state)
            next <- Game.applyMove(state, move)
          yield (next, next :: acc)
      }
      .map(_._2.reverse)

  /** Play a SAN line from the initial position and return the final state. */
  private def play(moves: List[String]): IO[GameError, GameState] =
    ZIO.foldLeft(moves)(GameState.initial) { (state, san) =>
      for
        move <- MoveParser.parse(san, state)
        next <- Game.applyMove(state, move)
      yield next
    }

  private val corpusSequences: List[List[String]] = List(
    // Ruy Lopez with both castling kingside
    List(
      "e4",
      "e5",
      "Nf3",
      "Nc6",
      "Bb5",
      "a6",
      "Ba4",
      "Nf6",
      "O-O",
      "Be7",
      "Re1",
      "b5",
      "Bb3",
      "d6",
      "c3",
      "O-O"
    ),
    // Najdorf with opposite-side castling
    List(
      "e4",
      "c5",
      "Nf3",
      "d6",
      "d4",
      "cxd4",
      "Nxd4",
      "Nf6",
      "Nc3",
      "a6",
      "Be3",
      "e5",
      "Nb3",
      "Be6",
      "f3",
      "b5",
      "Qd2",
      "Nbd7",
      "O-O-O",
      "Be7",
      "g4",
      "O-O"
    ),
    // En passant capture cycle
    List("e4", "Nf6", "e5", "d5", "exd6"),
    // Scholar's mate
    List("e4", "e5", "Qh5", "Nc6", "Bc4", "Nf6", "Qxf7#"),
    // Knight shuffle (generates repeated positions)
    List("Nf3", "Nc6", "Ng1", "Nb8", "Nf3", "Nc6", "Ng1", "Nb8")
  )

  def spec = suite("Zobrist.hash")(
    PositionIdentityBehaviors.behaviors(Zobrist.hash),
    test("hash of initial is non-zero and stable") {
      val h = Zobrist.hash(GameState.initial)
      assertTrue(h != 0L, h == Zobrist.hash(GameState.initial))
    },
    test(
      "distinctness sweep: Zobrist partitions corpus identically to FEN positionKey"
    ) {
      // For every pair of positions reachable in the corpus, assert that
      // their Zobrist hashes agree iff their FEN position-keys agree. This
      // is the definition of "equivalent as identity functions" — and the
      // strongest single test for Zobrist correctness, since it locks the
      // function to the known-good reference across every pair.
      for states <- ZIO.foreach(corpusSequences)(walkSequence).map(_.flatten)
      yield
        val pairs = for
          i <- states.indices
          j <- states.indices
          if i < j
        yield (states(i), states(j))
        val disagreements = pairs.filter { case (a, b) =>
          val fenEq = FenSerializer.positionKey(a) ==
            FenSerializer.positionKey(b)
          val zobEq = Zobrist.hash(a) == Zobrist.hash(b)
          fenEq != zobEq
        }
        assertTrue(disagreements.isEmpty)
    },
    test("pawnHash tracks the pawn skeleton, ignoring piece moves") {
      for
        afterKnight <- play(List("Nf3")) // only a knight moved
        afterPawn   <- play(List("e4"))  // a pawn moved
      yield assertTrue(
        // a non-pawn move leaves the pawn key unchanged …
        Zobrist.pawnHash(afterKnight) == Zobrist.pawnHash(GameState.initial),
        // … but moving a pawn changes it
        Zobrist.pawnHash(afterPawn) != Zobrist.pawnHash(GameState.initial),
      )
    },
    test("materialKey is stable across quiet moves and shifts on a capture") {
      for
        quiet    <- play(List("Nf3", "Nf6"))       // no material change
        captured <- play(List("e4", "d5", "exd5")) // White wins a pawn
      yield assertTrue(
        Zobrist.materialKey(quiet) == Zobrist.materialKey(GameState.initial),
        Zobrist.materialKey(captured) != Zobrist.materialKey(GameState.initial),
      )
    },
    test("pieceIndex and squareIndex encode the table-index scheme") {
      assertTrue(
        // pieceIndex: White King=0 … White Pawn=5, Black King=6 … Black Pawn=11
        Zobrist.pieceIndex(Piece(Color.White, PieceType.King)) == 0,
        Zobrist.pieceIndex(Piece(Color.White, PieceType.Pawn)) == 5,
        Zobrist.pieceIndex(Piece(Color.Black, PieceType.King)) == 6,
        Zobrist.pieceIndex(Piece(Color.Black, PieceType.Pawn)) == 11,
        // squareIndex: LERF (a1=0, h1=7, a2=8, h8=63)
        Zobrist.squareIndex(Position('a', 1)) == 0,
        Zobrist.squareIndex(Position('h', 1)) == 7,
        Zobrist.squareIndex(Position('a', 2)) == 8,
        Zobrist.squareIndex(Position('h', 8)) == 63,
      )
    }
  )
