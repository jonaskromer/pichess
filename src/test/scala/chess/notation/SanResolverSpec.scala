package chess.notation

import chess.model.board.{GameState, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SanResolverSpec extends AnyFlatSpec with Matchers:

  private val initial = GameState.initial

  // ─── Pawn push ─────────────────────────────────────────────────────────────

  "SanResolver.parse pawn push" should "find the pawn that can reach the destination" in:
    SanResolver.parse("e4", initial) shouldBe Some(
      Right(Move(Position('e', 2), Position('e', 4)))
    )

  it should "return None for non-SAN input" in:
    SanResolver.parse("e2 e4", initial) shouldBe None

  // ─── Pawn capture ─────────────────────────────────────────────────────────

  "SanResolver.parse pawn capture" should "use the file hint to find the correct pawn" in:
    val state = GameState(
      Map(
        Position('e', 4) -> Piece(Color.White, PieceType.Pawn),
        Position('d', 5) -> Piece(Color.Black, PieceType.Pawn)
      ),
      Color.White
    )
    SanResolver.parse("exd5", state) shouldBe Some(
      Right(Move(Position('e', 4), Position('d', 5)))
    )

  // ─── Piece move ────────────────────────────────────────────────────────────

  "SanResolver.parse piece move" should "find the knight that can reach the destination" in:
    SanResolver.parse("Nf3", initial) shouldBe Some(
      Right(Move(Position('g', 1), Position('f', 3)))
    )

  it should "fail when no piece of that type can reach the destination" in:
    val result = SanResolver.parse("Re4", initial)
    result shouldBe defined
    result.get shouldBe a[Left[?, ?]]

  it should "fail when the move is ambiguous" in:
    val state = GameState(
      Map(
        Position('a', 1) -> Piece(Color.White, PieceType.Rook),
        Position('f', 1) -> Piece(Color.White, PieceType.Rook)
      ),
      Color.White
    )
    val result = SanResolver.parse("Re1", state)
    result shouldBe defined
    result.get shouldBe a[Left[?, ?]]

  // ─── Disambiguation ────────────────────────────────────────────────────────

  "SanResolver.parse with file disambiguation" should "select the piece on the given file" in:
    val state = GameState(
      Map(
        Position('a', 1) -> Piece(Color.White, PieceType.Rook),
        Position('f', 1) -> Piece(Color.White, PieceType.Rook)
      ),
      Color.White
    )
    SanResolver.parse("Rae1", state) shouldBe Some(
      Right(Move(Position('a', 1), Position('e', 1)))
    )

  "SanResolver.parse with rank disambiguation" should "select the piece on the given rank" in:
    val state = GameState(
      Map(
        Position('a', 1) -> Piece(Color.White, PieceType.Rook),
        Position('a', 5) -> Piece(Color.White, PieceType.Rook)
      ),
      Color.White
    )
    SanResolver.parse("R1a3", state) shouldBe Some(
      Right(Move(Position('a', 1), Position('a', 3)))
    )
