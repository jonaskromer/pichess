package chess.controller

import chess.model.board.{GameState, Move, Position}
import chess.model.piece.{Color, Piece, PieceType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MoveParserSpec extends AnyFlatSpec with Matchers:

  private val initial = GameState.initial

  // ─── Coordinate notation ───────────────────────────────────────────────────

  "MoveParser.parse coordinate" should "parse space-separated squares" in:
    MoveParser.parse("e2 e4", initial) shouldBe Right(
      Move(Position('e', 2), Position('e', 4))
    )

  it should "parse corner squares" in:
    MoveParser.parse("a1 h8", initial) shouldBe Right(
      Move(Position('a', 1), Position('h', 8))
    )

  it should "parse squares with multiple spaces" in:
    MoveParser.parse("e2  e4", initial) shouldBe Right(
      Move(Position('e', 2), Position('e', 4))
    )

  it should "parse squares with no separator" in:
    MoveParser.parse("e2e4", initial) shouldBe Right(
      Move(Position('e', 2), Position('e', 4))
    )

  it should "parse squares separated by a dash" in:
    MoveParser.parse("e2-e4", initial) shouldBe Right(
      Move(Position('e', 2), Position('e', 4))
    )

  // ─── Coordinate promotion ────────────────────────────────────────────────

  "MoveParser.parse coordinate promotion" should "parse with no separator" in:
    MoveParser.parse("e7e8=Q", initial) shouldBe Right(
      Move(Position('e', 7), Position('e', 8), Some(PieceType.Queen))
    )

  it should "parse with space separator" in:
    MoveParser.parse("e7 e8=Q", initial) shouldBe Right(
      Move(Position('e', 7), Position('e', 8), Some(PieceType.Queen))
    )

  it should "parse with dash separator" in:
    MoveParser.parse("e7-e8=Q", initial) shouldBe Right(
      Move(Position('e', 7), Position('e', 8), Some(PieceType.Queen))
    )

  // ─── Pawn push (SAN) ─────────────────────────────────────────────────────

  "MoveParser.parse pawn push" should "resolve a pawn push from the initial position" in:
    MoveParser.parse("e4", initial) shouldBe Right(
      Move(Position('e', 2), Position('e', 4))
    )

  it should "accept a check suffix" in:
    val state = GameState(
      Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)),
      Color.White
    )
    MoveParser.parse("e8=Q+", state).isRight shouldBe true

  it should "accept a checkmate suffix" in:
    val state = GameState(
      Map(Position('d', 2) -> Piece(Color.Black, PieceType.Pawn)),
      Color.Black
    )
    MoveParser.parse("d1=Q#", state).isRight shouldBe true

  // ─── Pawn capture (SAN) ──────────────────────────────────────────────────

  "MoveParser.parse pawn capture" should "resolve with file hint" in:
    val state = GameState(
      Map(
        Position('e', 4) -> Piece(Color.White, PieceType.Pawn),
        Position('d', 5) -> Piece(Color.Black, PieceType.Pawn)
      ),
      Color.White
    )
    MoveParser.parse("exd5", state) shouldBe Right(
      Move(Position('e', 4), Position('d', 5))
    )

  it should "resolve a pawn capture with promotion" in:
    val state = GameState(
      Map(
        Position('e', 7) -> Piece(Color.White, PieceType.Pawn),
        Position('d', 8) -> Piece(Color.Black, PieceType.Rook)
      ),
      Color.White
    )
    MoveParser.parse("exd8=Q", state) shouldBe Right(
      Move(Position('e', 7), Position('d', 8), Some(PieceType.Queen))
    )

  // ─── Pawn promotion (SAN) ────────────────────────────────────────────────

  "MoveParser.parse pawn promotion" should "resolve a queen promotion" in:
    val state = GameState(
      Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)),
      Color.White
    )
    MoveParser.parse("e8=Q", state) shouldBe Right(
      Move(Position('e', 7), Position('e', 8), Some(PieceType.Queen))
    )

  it should "resolve a knight underpromotion" in:
    val state = GameState(
      Map(Position('e', 7) -> Piece(Color.White, PieceType.Pawn)),
      Color.White
    )
    MoveParser.parse("e8=N", state) shouldBe Right(
      Move(Position('e', 7), Position('e', 8), Some(PieceType.Knight))
    )

  it should "resolve a rook underpromotion" in:
    val state = GameState(
      Map(Position('a', 2) -> Piece(Color.Black, PieceType.Pawn)),
      Color.Black
    )
    MoveParser.parse("a1=R", state) shouldBe Right(
      Move(Position('a', 2), Position('a', 1), Some(PieceType.Rook))
    )

  it should "resolve a bishop underpromotion" in:
    val state = GameState(
      Map(Position('d', 7) -> Piece(Color.White, PieceType.Pawn)),
      Color.White
    )
    MoveParser.parse("d8=B", state) shouldBe Right(
      Move(Position('d', 7), Position('d', 8), Some(PieceType.Bishop))
    )

  it should "resolve a capture with knight underpromotion" in:
    val state = GameState(
      Map(
        Position('e', 7) -> Piece(Color.White, PieceType.Pawn),
        Position('d', 8) -> Piece(Color.Black, PieceType.Rook)
      ),
      Color.White
    )
    MoveParser.parse("exd8=N", state) shouldBe Right(
      Move(Position('e', 7), Position('d', 8), Some(PieceType.Knight))
    )

  // ─── Piece moves (SAN) ───────────────────────────────────────────────────

  "MoveParser.parse piece move" should "resolve a knight move" in:
    MoveParser.parse("Nf3", initial) shouldBe Right(
      Move(Position('g', 1), Position('f', 3))
    )

  it should "resolve a piece capture" in:
    val state = GameState(
      Map(
        Position('g', 1) -> Piece(Color.White, PieceType.Knight),
        Position('f', 3) -> Piece(Color.Black, PieceType.Pawn)
      ),
      Color.White
    )
    MoveParser.parse("Nxf3", state) shouldBe Right(
      Move(Position('g', 1), Position('f', 3))
    )

  it should "accept a check suffix" in:
    MoveParser.parse("Nf3+", initial).isRight shouldBe true

  it should "accept a checkmate suffix" in:
    val state = GameState(
      Map(
        Position('h', 5) -> Piece(Color.White, PieceType.Queen),
        Position('f', 7) -> Piece(Color.Black, PieceType.Pawn)
      ),
      Color.White
    )
    MoveParser.parse("Qxf7#", state).isRight shouldBe true

  // ─── Disambiguation (SAN) ────────────────────────────────────────────────

  "MoveParser.parse disambiguation" should "resolve with file disambiguation" in:
    val state = GameState(
      Map(
        Position('a', 1) -> Piece(Color.White, PieceType.Rook),
        Position('f', 1) -> Piece(Color.White, PieceType.Rook)
      ),
      Color.White
    )
    MoveParser.parse("Rae1", state) shouldBe Right(
      Move(Position('a', 1), Position('e', 1))
    )

  it should "resolve with rank disambiguation" in:
    val state = GameState(
      Map(
        Position('a', 1) -> Piece(Color.White, PieceType.Rook),
        Position('a', 5) -> Piece(Color.White, PieceType.Rook)
      ),
      Color.White
    )
    MoveParser.parse("R1a3", state) shouldBe Right(
      Move(Position('a', 1), Position('a', 3))
    )

  // ─── Castling ────────────────────────────────────────────────────────────

  "MoveParser.parse castling" should "fail for kingside with a not-implemented error" in:
    val result = MoveParser.parse("O-O", initial)
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.message should include("Kingside")

  it should "fail for queenside with a not-implemented error" in:
    val result = MoveParser.parse("O-O-O", initial)
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.message should include("Queenside")

  it should "accept a check suffix" in:
    val result = MoveParser.parse("O-O+", initial)
    result shouldBe a[Left[?, ?]]
    result.left.toOption.get.message should include("Kingside")

  // ─── Error cases ─────────────────────────────────────────────────────────

  "MoveParser.parse errors" should "reject three or more tokens" in:
    MoveParser.parse("e2 e4 d5", initial) shouldBe a[Left[?, ?]]

  it should "reject empty input" in:
    MoveParser.parse("", initial) shouldBe a[Left[?, ?]]

  it should "reject an invalid column" in:
    MoveParser.parse("i2 e4", initial) shouldBe a[Left[?, ?]]

  it should "reject a row above 8" in:
    MoveParser.parse("e9 e4", initial) shouldBe a[Left[?, ?]]

  it should "reject a row of 0" in:
    MoveParser.parse("e0 e4", initial) shouldBe a[Left[?, ?]]

  it should "reject an invalid destination column" in:
    MoveParser.parse("e2 z4", initial) shouldBe a[Left[?, ?]]

  it should "reject an invalid destination row" in:
    MoveParser.parse("e2 e9", initial) shouldBe a[Left[?, ?]]

  it should "reject a token that is too long" in:
    MoveParser.parse("e22 e4", initial) shouldBe a[Left[?, ?]]

  it should "reject a token that is too short" in:
    MoveParser.parse("e e4", initial) shouldBe a[Left[?, ?]]

  it should "include a hint pointing to help in error messages" in:
    MoveParser
      .parse("nonsense", initial)
      .swap
      .toOption
      .get
      .asInstanceOf[chess.model.GameError]
      .message should include("help")
