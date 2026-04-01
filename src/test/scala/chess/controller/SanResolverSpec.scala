package chess.controller

import chess.model.board.{GameState, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SanResolverSpec extends AnyFlatSpec with Matchers:

  private val initial = GameState.initial

  // ─── Coordinate passthrough ────────────────────────────────────────────────

  "SanResolver.resolve Coordinate" should "pass through as a Move unchanged" in:
    SanResolver.resolve(
      ParsedMove.Coordinate(Position('e', 2), Position('e', 4)),
      initial
    ) shouldBe Right(Move(Position('e', 2), Position('e', 4)))

  // ─── Castling ──────────────────────────────────────────────────────────────

  "SanResolver.resolve Castling" should "fail for kingside with a not-implemented error" in:
    val result = SanResolver.resolve(ParsedMove.Castling(true), initial)
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.message should include("Kingside")

  it should "fail for queenside with a not-implemented error" in:
    val result = SanResolver.resolve(ParsedMove.Castling(false), initial)
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.message should include("Queenside")

  // ─── Pawn push ─────────────────────────────────────────────────────────────

  "SanResolver.resolve San pawn push" should "find the pawn that can reach the destination" in:
    // White pawn on e2 can push to e4 (double advance from start)
    SanResolver.resolve(
      ParsedMove.San(PieceType.Pawn, Position('e', 4), None, None),
      initial
    ) shouldBe Right(Move(Position('e', 2), Position('e', 4)))

  // ─── Pawn capture ──────────────────────────────────────────────────────────

  "SanResolver.resolve San pawn capture" should "use the file hint to find the correct pawn" in:
    val board = Map(
      Position('e', 4) -> Piece(Color.White, PieceType.Pawn),
      Position('d', 5) -> Piece(Color.Black, PieceType.Pawn)
    )
    val state = GameState(board, Color.White)
    SanResolver.resolve(
      ParsedMove.San(PieceType.Pawn, Position('d', 5), Some('e'), None),
      state
    ) shouldBe Right(Move(Position('e', 4), Position('d', 5)))

  // ─── Piece move ────────────────────────────────────────────────────────────

  "SanResolver.resolve San piece move" should "find the knight that can reach the destination" in:
    // White knight on g1 → f3; b1-knight cannot reach f3 from initial position
    SanResolver.resolve(
      ParsedMove.San(PieceType.Knight, Position('f', 3), None, None),
      initial
    ) shouldBe Right(Move(Position('g', 1), Position('f', 3)))

  it should "fail when no piece of that type can reach the destination" in:
    // White rooks at a1 and h1 are both blocked by pawns on initial board
    val result = SanResolver.resolve(
      ParsedMove.San(PieceType.Rook, Position('e', 4), None, None),
      initial
    )
    result shouldBe a[Left[?, ?]]

  it should "fail when the move is ambiguous" in:
    val board = Map(
      Position('a', 1) -> Piece(Color.White, PieceType.Rook),
      Position('f', 1) -> Piece(Color.White, PieceType.Rook)
    )
    val state = GameState(board, Color.White)
    // Both rooks can slide to e1
    val result = SanResolver.resolve(
      ParsedMove.San(PieceType.Rook, Position('e', 1), None, None),
      state
    )
    result shouldBe a[Left[?, ?]]

  // ─── Disambiguation ────────────────────────────────────────────────────────

  "SanResolver.resolve San with file disambiguation" should "select the piece on the given file" in:
    val board = Map(
      Position('a', 1) -> Piece(Color.White, PieceType.Rook),
      Position('f', 1) -> Piece(Color.White, PieceType.Rook)
    )
    val state = GameState(board, Color.White)
    SanResolver.resolve(
      ParsedMove.San(PieceType.Rook, Position('e', 1), Some('a'), None),
      state
    ) shouldBe Right(Move(Position('a', 1), Position('e', 1)))

  "SanResolver.resolve San with rank disambiguation" should "select the piece on the given rank" in:
    val board = Map(
      Position('a', 1) -> Piece(Color.White, PieceType.Rook),
      Position('a', 5) -> Piece(Color.White, PieceType.Rook)
    )
    val state = GameState(board, Color.White)
    SanResolver.resolve(
      ParsedMove.San(PieceType.Rook, Position('a', 3), None, Some(1)),
      state
    ) shouldBe Right(Move(Position('a', 1), Position('a', 3)))
