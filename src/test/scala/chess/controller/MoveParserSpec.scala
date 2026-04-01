package chess.controller

import chess.model.board.Position
import chess.model.piece.PieceType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MoveParserSpec extends AnyFlatSpec with Matchers:

  // ─── Coordinate notation ───────────────────────────────────────────────────

  "MoveParser.parse coordinate" should "parse space-separated squares" in:
    MoveParser.parse("e2 e4") shouldBe Right(
      ParsedMove.Coordinate(Position('e', 2), Position('e', 4))
    )

  it should "parse corner squares" in:
    MoveParser.parse("a1 h8") shouldBe Right(
      ParsedMove.Coordinate(Position('a', 1), Position('h', 8))
    )

  it should "parse squares with multiple spaces" in:
    MoveParser.parse("e2  e4") shouldBe Right(
      ParsedMove.Coordinate(Position('e', 2), Position('e', 4))
    )

  it should "parse squares with no separator" in:
    MoveParser.parse("e2e4") shouldBe Right(
      ParsedMove.Coordinate(Position('e', 2), Position('e', 4))
    )

  it should "parse squares separated by a dash" in:
    MoveParser.parse("e2-e4") shouldBe Right(
      ParsedMove.Coordinate(Position('e', 2), Position('e', 4))
    )

  // ─── Pawn push ─────────────────────────────────────────────────────────────

  "MoveParser.parse pawn push" should "parse a destination square as a pawn push" in:
    MoveParser.parse("e4") shouldBe Right(
      ParsedMove.San(PieceType.Pawn, Position('e', 4), None, None)
    )

  it should "parse a pawn push with check suffix" in:
    MoveParser.parse("e8+") shouldBe Right(
      ParsedMove.San(PieceType.Pawn, Position('e', 8), None, None)
    )

  it should "parse a pawn push with checkmate suffix" in:
    MoveParser.parse("d1#") shouldBe Right(
      ParsedMove.San(PieceType.Pawn, Position('d', 1), None, None)
    )

  // ─── Pawn capture ──────────────────────────────────────────────────────────

  "MoveParser.parse pawn capture" should "parse fromFile x dest" in:
    MoveParser.parse("exd5") shouldBe Right(
      ParsedMove.San(PieceType.Pawn, Position('d', 5), Some('e'), None)
    )

  it should "parse a pawn capture with promotion suffix" in:
    MoveParser.parse("exd8=Q") shouldBe Right(
      ParsedMove.San(
        PieceType.Pawn,
        Position('d', 8),
        Some('e'),
        None,
        Some(PieceType.Queen)
      )
    )

  it should "parse a pawn capture with check suffix" in:
    MoveParser.parse("cxb4+") shouldBe Right(
      ParsedMove.San(PieceType.Pawn, Position('b', 4), Some('c'), None)
    )

  // ─── Pawn promotion ───────────────────────────────────────────────────────

  "MoveParser.parse pawn promotion" should "parse a pawn push with promotion" in:
    MoveParser.parse("e8=Q") shouldBe Right(
      ParsedMove.San(PieceType.Pawn, Position('e', 8), None, None, Some(PieceType.Queen))
    )

  it should "parse a pawn push with knight promotion" in:
    MoveParser.parse("e8=N") shouldBe Right(
      ParsedMove.San(PieceType.Pawn, Position('e', 8), None, None, Some(PieceType.Knight))
    )

  it should "parse a pawn push with rook promotion" in:
    MoveParser.parse("a1=R") shouldBe Right(
      ParsedMove.San(PieceType.Pawn, Position('a', 1), None, None, Some(PieceType.Rook))
    )

  it should "parse a pawn push with bishop promotion" in:
    MoveParser.parse("d8=B") shouldBe Right(
      ParsedMove.San(PieceType.Pawn, Position('d', 8), None, None, Some(PieceType.Bishop))
    )

  it should "parse a pawn capture with knight underpromotion" in:
    MoveParser.parse("exd8=N") shouldBe Right(
      ParsedMove.San(
        PieceType.Pawn,
        Position('d', 8),
        Some('e'),
        None,
        Some(PieceType.Knight)
      )
    )

  it should "parse coordinate notation with promotion (no separator)" in:
    MoveParser.parse("e7e8=Q") shouldBe Right(
      ParsedMove.Coordinate(Position('e', 7), Position('e', 8), Some(PieceType.Queen))
    )

  it should "parse coordinate notation with promotion (space separator)" in:
    MoveParser.parse("e7 e8=Q") shouldBe Right(
      ParsedMove.Coordinate(Position('e', 7), Position('e', 8), Some(PieceType.Queen))
    )

  it should "parse coordinate notation with promotion (dash separator)" in:
    MoveParser.parse("e7-e8=Q") shouldBe Right(
      ParsedMove.Coordinate(Position('e', 7), Position('e', 8), Some(PieceType.Queen))
    )

  // ─── Piece moves ───────────────────────────────────────────────────────────

  "MoveParser.parse piece move" should "parse a knight move" in:
    MoveParser.parse("Nf3") shouldBe Right(
      ParsedMove.San(PieceType.Knight, Position('f', 3), None, None)
    )

  it should "parse a bishop move" in:
    MoveParser.parse("Bc4") shouldBe Right(
      ParsedMove.San(PieceType.Bishop, Position('c', 4), None, None)
    )

  it should "parse a rook move" in:
    MoveParser.parse("Rd1") shouldBe Right(
      ParsedMove.San(PieceType.Rook, Position('d', 1), None, None)
    )

  it should "parse a queen move" in:
    MoveParser.parse("Qd8") shouldBe Right(
      ParsedMove.San(PieceType.Queen, Position('d', 8), None, None)
    )

  it should "parse a king move" in:
    MoveParser.parse("Ke2") shouldBe Right(
      ParsedMove.San(PieceType.King, Position('e', 2), None, None)
    )

  it should "parse a piece capture" in:
    MoveParser.parse("Nxf3") shouldBe Right(
      ParsedMove.San(PieceType.Knight, Position('f', 3), None, None)
    )

  it should "parse a piece move with check suffix" in:
    MoveParser.parse("Nf3+") shouldBe Right(
      ParsedMove.San(PieceType.Knight, Position('f', 3), None, None)
    )

  it should "parse a piece move with checkmate suffix" in:
    MoveParser.parse("Qxf7#") shouldBe Right(
      ParsedMove.San(PieceType.Queen, Position('f', 7), None, None)
    )

  // ─── Disambiguation ────────────────────────────────────────────────────────

  "MoveParser.parse disambiguation" should "parse file disambiguation" in:
    MoveParser.parse("Nbd2") shouldBe Right(
      ParsedMove.San(PieceType.Knight, Position('d', 2), Some('b'), None)
    )

  it should "parse rank disambiguation" in:
    MoveParser.parse("N1f3") shouldBe Right(
      ParsedMove.San(PieceType.Knight, Position('f', 3), None, Some(1))
    )

  it should "parse file-and-rank disambiguation" in:
    MoveParser.parse("Rd2f2") shouldBe Right(
      ParsedMove.San(PieceType.Rook, Position('f', 2), Some('d'), Some(2))
    )

  it should "parse a file-disambiguated capture" in:
    MoveParser.parse("Raxd5") shouldBe Right(
      ParsedMove.San(PieceType.Rook, Position('d', 5), Some('a'), None)
    )

  // ─── Castling ──────────────────────────────────────────────────────────────

  "MoveParser.parse castling" should "parse kingside castling" in:
    MoveParser.parse("O-O") shouldBe Right(ParsedMove.Castling(true))

  it should "parse queenside castling" in:
    MoveParser.parse("O-O-O") shouldBe Right(ParsedMove.Castling(false))

  it should "parse kingside castling with check suffix" in:
    MoveParser.parse("O-O+") shouldBe Right(ParsedMove.Castling(true))

  // ─── Error cases ───────────────────────────────────────────────────────────

  "MoveParser.parse errors" should "reject three or more tokens" in:
    MoveParser.parse("e2 e4 d5") shouldBe a[Left[?, ?]]

  it should "reject empty input" in:
    MoveParser.parse("") shouldBe a[Left[?, ?]]

  it should "reject an invalid column" in:
    MoveParser.parse("i2 e4") shouldBe a[Left[?, ?]]

  it should "reject a row above 8" in:
    MoveParser.parse("e9 e4") shouldBe a[Left[?, ?]]

  it should "reject a row of 0" in:
    MoveParser.parse("e0 e4") shouldBe a[Left[?, ?]]

  it should "reject an invalid destination column" in:
    MoveParser.parse("e2 z4") shouldBe a[Left[?, ?]]

  it should "reject an invalid destination row" in:
    MoveParser.parse("e2 e9") shouldBe a[Left[?, ?]]

  it should "reject a token that is too long" in:
    MoveParser.parse("e22 e4") shouldBe a[Left[?, ?]]

  it should "reject a token that is too short" in:
    MoveParser.parse("e e4") shouldBe a[Left[?, ?]]

  it should "include a hint pointing to help in error messages" in:
    MoveParser
      .parse("nonsense")
      .swap
      .toOption
      .get
      .asInstanceOf[chess.model.GameError]
      .message should include("help")
