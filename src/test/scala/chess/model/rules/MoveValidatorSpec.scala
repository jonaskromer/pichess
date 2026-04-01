package chess.model.rules

import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{GameState, Move, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MoveValidatorSpec extends AnyFlatSpec with Matchers:

  // Helpers
  private def state(pieces: (Position, Piece)*): GameState =
    GameState(pieces.toMap, Color.White)

  private def blackState(pieces: (Position, Piece)*): GameState =
    GameState(pieces.toMap, Color.Black)

  private def pos(col: Char, row: Int): Position = Position(col, row)

  private val WP = Piece(Color.White, PieceType.Pawn)
  private val BP = Piece(Color.Black, PieceType.Pawn)
  private val WR = Piece(Color.White, PieceType.Rook)
  private val BR = Piece(Color.Black, PieceType.Rook)
  private val WB = Piece(Color.White, PieceType.Bishop)
  private val BB = Piece(Color.Black, PieceType.Bishop)
  private val WQ = Piece(Color.White, PieceType.Queen)
  private val BQ = Piece(Color.Black, PieceType.Queen)
  private val WN = Piece(Color.White, PieceType.Knight)
  private val BN = Piece(Color.Black, PieceType.Knight)
  private val WK = Piece(Color.White, PieceType.King)
  private val BK = Piece(Color.Black, PieceType.King)

  // ─── Pawn (White) ───────────────────────────────────────────────────────────

  "MoveValidator" should "allow white pawn to move one square forward" in:
    val s = state(pos('e', 2) -> WP)
    MoveValidator.validate(s, Move(pos('e', 2), pos('e', 3))) shouldBe Right(())

  it should "allow white pawn to move two squares forward from rank 2" in:
    val s = state(pos('e', 2) -> WP)
    MoveValidator.validate(s, Move(pos('e', 2), pos('e', 4))) shouldBe Right(())

  it should "reject white pawn two-square advance from non-starting rank" in:
    val s = state(pos('e', 3) -> WP)
    MoveValidator
      .validate(s, Move(pos('e', 3), pos('e', 5)))
      .isLeft shouldBe true

  it should "reject white pawn two-square advance when intermediate square is blocked" in:
    val s = state(pos('e', 2) -> WP, pos('e', 3) -> BP)
    MoveValidator
      .validate(s, Move(pos('e', 2), pos('e', 4)))
      .isLeft shouldBe true

  it should "reject white pawn two-square advance when destination is occupied but path is clear" in:
    val s = state(pos('e', 2) -> WP, pos('e', 4) -> BP)
    MoveValidator
      .validate(s, Move(pos('e', 2), pos('e', 4)))
      .isLeft shouldBe true

  it should "reject white pawn move forward when destination is occupied" in:
    val s = state(pos('e', 3) -> WP, pos('e', 4) -> BP)
    MoveValidator
      .validate(s, Move(pos('e', 3), pos('e', 4)))
      .isLeft shouldBe true

  it should "allow white pawn diagonal capture of enemy piece" in:
    val s = state(pos('e', 3) -> WP, pos('f', 4) -> BP)
    MoveValidator.validate(s, Move(pos('e', 3), pos('f', 4))) shouldBe Right(())

  it should "reject white pawn diagonal move to empty square" in:
    val s = state(pos('e', 3) -> WP)
    MoveValidator
      .validate(s, Move(pos('e', 3), pos('f', 4)))
      .isLeft shouldBe true

  it should "reject white pawn moving backward" in:
    val s = state(pos('e', 3) -> WP)
    MoveValidator
      .validate(s, Move(pos('e', 3), pos('e', 2)))
      .isLeft shouldBe true

  it should "reject white pawn moving sideways" in:
    val s = state(pos('e', 3) -> WP)
    MoveValidator
      .validate(s, Move(pos('e', 3), pos('f', 3)))
      .isLeft shouldBe true

  // ─── Pawn (Black) ───────────────────────────────────────────────────────────

  it should "allow black pawn to move one square forward (toward rank 1)" in:
    val s = blackState(pos('e', 7) -> BP)
    MoveValidator.validate(s, Move(pos('e', 7), pos('e', 6))) shouldBe Right(())

  it should "allow black pawn to move two squares forward from rank 7" in:
    val s = blackState(pos('e', 7) -> BP)
    MoveValidator.validate(s, Move(pos('e', 7), pos('e', 5))) shouldBe Right(())

  it should "reject black pawn two-square advance from non-starting rank" in:
    val s = blackState(pos('e', 6) -> BP)
    MoveValidator
      .validate(s, Move(pos('e', 6), pos('e', 4)))
      .isLeft shouldBe true

  it should "allow black pawn diagonal capture of enemy piece" in:
    val s = blackState(pos('e', 7) -> BP, pos('f', 6) -> WP)
    MoveValidator.validate(s, Move(pos('e', 7), pos('f', 6))) shouldBe Right(())

  // ─── Rook ───────────────────────────────────────────────────────────────────

  it should "allow rook to move horizontally on a clear rank" in:
    val s = state(pos('a', 1) -> WR)
    MoveValidator.validate(s, Move(pos('a', 1), pos('h', 1))) shouldBe Right(())

  it should "allow rook to move vertically on a clear file" in:
    val s = state(pos('a', 1) -> WR)
    MoveValidator.validate(s, Move(pos('a', 1), pos('a', 8))) shouldBe Right(())

  it should "reject rook moving diagonally" in:
    val s = state(pos('a', 1) -> WR)
    MoveValidator
      .validate(s, Move(pos('a', 1), pos('b', 2)))
      .isLeft shouldBe true

  it should "reject rook moving in a knight-leap shape" in:
    val s = state(pos('a', 1) -> WR)
    MoveValidator
      .validate(s, Move(pos('a', 1), pos('c', 2)))
      .isLeft shouldBe true

  it should "reject rook moving in an irregular direction" in:
    val s = state(pos('a', 1) -> WR)
    MoveValidator
      .validate(s, Move(pos('a', 1), pos('c', 4)))
      .isLeft shouldBe true

  it should "reject rook move when path is blocked" in:
    val s = state(pos('a', 1) -> WR, pos('c', 1) -> BP)
    MoveValidator
      .validate(s, Move(pos('a', 1), pos('h', 1)))
      .isLeft shouldBe true

  it should "allow rook to capture at the end of a clear path" in:
    val s = state(pos('a', 1) -> WR, pos('h', 1) -> BP)
    MoveValidator.validate(s, Move(pos('a', 1), pos('h', 1))) shouldBe Right(())

  // ─── Bishop ─────────────────────────────────────────────────────────────────

  it should "allow bishop to move diagonally on a clear path" in:
    val s = state(pos('a', 1) -> WB)
    MoveValidator.validate(s, Move(pos('a', 1), pos('d', 4))) shouldBe Right(())

  it should "allow bishop to move diagonally in the other direction" in:
    val s = state(pos('d', 4) -> WB)
    MoveValidator.validate(s, Move(pos('d', 4), pos('a', 1))) shouldBe Right(())

  it should "reject bishop moving horizontally" in:
    val s = state(pos('a', 1) -> WB)
    MoveValidator
      .validate(s, Move(pos('a', 1), pos('h', 1)))
      .isLeft shouldBe true

  it should "reject bishop moving vertically" in:
    val s = state(pos('a', 1) -> WB)
    MoveValidator
      .validate(s, Move(pos('a', 1), pos('a', 8)))
      .isLeft shouldBe true

  it should "reject bishop moving in a knight-leap shape" in:
    val s = state(pos('a', 1) -> WB)
    MoveValidator
      .validate(s, Move(pos('a', 1), pos('c', 2)))
      .isLeft shouldBe true

  it should "reject bishop moving in an irregular direction" in:
    val s = state(pos('a', 1) -> WB)
    MoveValidator
      .validate(s, Move(pos('a', 1), pos('c', 4)))
      .isLeft shouldBe true

  it should "reject bishop moving to its own square (zero move)" in:
    val s = state(pos('d', 4) -> WB)
    MoveValidator
      .validate(s, Move(pos('d', 4), pos('d', 4)))
      .isLeft shouldBe true

  it should "reject bishop move when diagonal path is blocked" in:
    val s = state(pos('a', 1) -> WB, pos('b', 2) -> BP)
    MoveValidator
      .validate(s, Move(pos('a', 1), pos('d', 4)))
      .isLeft shouldBe true

  // ─── Queen ──────────────────────────────────────────────────────────────────

  it should "allow queen to move horizontally" in:
    val s = state(pos('d', 1) -> WQ)
    MoveValidator.validate(s, Move(pos('d', 1), pos('h', 1))) shouldBe Right(())

  it should "allow queen to move vertically" in:
    val s = state(pos('d', 1) -> WQ)
    MoveValidator.validate(s, Move(pos('d', 1), pos('d', 8))) shouldBe Right(())

  it should "allow queen to move diagonally" in:
    val s = state(pos('d', 1) -> WQ)
    MoveValidator.validate(s, Move(pos('d', 1), pos('g', 4))) shouldBe Right(())

  it should "reject queen moving in a knight-leap shape" in:
    val s = state(pos('d', 1) -> WQ)
    MoveValidator
      .validate(s, Move(pos('d', 1), pos('f', 2)))
      .isLeft shouldBe true

  it should "reject queen moving in an irregular direction" in:
    val s = state(pos('d', 1) -> WQ)
    MoveValidator
      .validate(s, Move(pos('d', 1), pos('e', 3)))
      .isLeft shouldBe true

  it should "reject queen moving to its own square (zero move)" in:
    val s = state(pos('d', 1) -> WQ)
    MoveValidator
      .validate(s, Move(pos('d', 1), pos('d', 1)))
      .isLeft shouldBe true

  it should "reject queen move when path is blocked" in:
    val s = state(pos('d', 1) -> WQ, pos('f', 1) -> BP)
    MoveValidator
      .validate(s, Move(pos('d', 1), pos('h', 1)))
      .isLeft shouldBe true

  // ─── Knight ─────────────────────────────────────────────────────────────────

  it should "allow knight to move in L-shape (2 forward, 1 side)" in:
    val s = state(pos('g', 1) -> WN)
    MoveValidator.validate(s, Move(pos('g', 1), pos('f', 3))) shouldBe Right(())

  it should "allow knight to move in L-shape (1 forward, 2 side)" in:
    val s = state(pos('g', 1) -> WN)
    MoveValidator.validate(s, Move(pos('g', 1), pos('h', 3))) shouldBe Right(())

  it should "allow knight to move in L-shape (2 side, 1 forward)" in:
    val s = state(pos('b', 1) -> WN)
    MoveValidator.validate(s, Move(pos('b', 1), pos('d', 2))) shouldBe Right(())

  it should "allow knight to jump over pieces" in:
    val s = state(pos('g', 1) -> WN, pos('g', 2) -> BP, pos('f', 2) -> BP)
    MoveValidator.validate(s, Move(pos('g', 1), pos('f', 3))) shouldBe Right(())

  it should "reject knight moving straight" in:
    val s = state(pos('g', 1) -> WN)
    MoveValidator
      .validate(s, Move(pos('g', 1), pos('g', 3)))
      .isLeft shouldBe true

  it should "reject knight moving diagonally one step" in:
    val s = state(pos('g', 1) -> WN)
    MoveValidator
      .validate(s, Move(pos('g', 1), pos('h', 2)))
      .isLeft shouldBe true

  it should "reject knight moving horizontally" in:
    val s = state(pos('a', 1) -> WN)
    MoveValidator
      .validate(s, Move(pos('a', 1), pos('h', 1)))
      .isLeft shouldBe true

  it should "reject knight moving in an irregular direction" in:
    val s = state(pos('a', 1) -> WN)
    MoveValidator
      .validate(s, Move(pos('a', 1), pos('d', 4)))
      .isLeft shouldBe true

  // ─── King ───────────────────────────────────────────────────────────────────

  it should "allow king to move one square horizontally" in:
    val s = state(pos('e', 1) -> WK)
    MoveValidator.validate(s, Move(pos('e', 1), pos('f', 1))) shouldBe Right(())

  it should "allow king to move one square vertically" in:
    val s = state(pos('e', 1) -> WK)
    MoveValidator.validate(s, Move(pos('e', 1), pos('e', 2))) shouldBe Right(())

  it should "allow king to move one square diagonally" in:
    val s = state(pos('e', 1) -> WK)
    MoveValidator.validate(s, Move(pos('e', 1), pos('f', 2))) shouldBe Right(())

  it should "reject king moving two squares" in:
    val s = state(pos('e', 1) -> WK)
    MoveValidator
      .validate(s, Move(pos('e', 1), pos('g', 1)))
      .isLeft shouldBe true

  it should "reject king moving in a knight-leap shape" in:
    val s = state(pos('e', 1) -> WK)
    MoveValidator
      .validate(s, Move(pos('e', 1), pos('g', 2)))
      .isLeft shouldBe true

  it should "reject king moving to its own square (zero move)" in:
    val s = state(pos('e', 1) -> WK)
    MoveValidator
      .validate(s, Move(pos('e', 1), pos('e', 1)))
      .isLeft shouldBe true

  // ─── Own piece capture ──────────────────────────────────────────────────────

  it should "reject any move that would capture a piece of the same color" in:
    val s = state(pos('a', 1) -> WR, pos('h', 1) -> WP)
    MoveValidator
      .validate(s, Move(pos('a', 1), pos('h', 1)))
      .isLeft shouldBe true

  // ─── En passant ─────────────────────────────────────────────────────────────

  it should "allow white pawn en passant capture toward the en passant target" in:
    val s = GameState(
      Map(pos('e', 5) -> WP, pos('d', 5) -> BP),
      Color.White,
      enPassantTarget = Some(pos('d', 6))
    )
    MoveValidator.validate(s, Move(pos('e', 5), pos('d', 6))) shouldBe Right(())

  it should "allow black pawn en passant capture toward the en passant target" in:
    val s = GameState(
      Map(pos('d', 4) -> BP, pos('e', 4) -> WP),
      Color.Black,
      enPassantTarget = Some(pos('e', 3))
    )
    MoveValidator.validate(s, Move(pos('d', 4), pos('e', 3))) shouldBe Right(())

  it should "reject pawn diagonal move to empty square when no en passant target is set" in:
    val s = state(pos('e', 5) -> WP)
    MoveValidator
      .validate(s, Move(pos('e', 5), pos('d', 6)))
      .isLeft shouldBe true

  it should "reject pawn diagonal move to a square that is not the en passant target" in:
    val s = GameState(
      Map(pos('e', 5) -> WP),
      Color.White,
      enPassantTarget = Some(pos('f', 6))
    )
    MoveValidator
      .validate(s, Move(pos('e', 5), pos('d', 6)))
      .isLeft shouldBe true

  // ─── Source square / turn validation ────────────────────────────────────────

  it should "return Left with source position when source square is empty" in:
    val s = state(pos('a', 1) -> WR)
    val result = MoveValidator.validate(s, Move(pos('e', 4), pos('e', 5)))
    result.isLeft shouldBe true
    result.swap.toOption.get
      .asInstanceOf[chess.model.GameError]
      .message should include("e4")

  it should "return Left mentioning the piece color when moving opponent's piece" in:
    val s = state(pos('e', 7) -> BP)
    val result = MoveValidator.validate(s, Move(pos('e', 7), pos('e', 6)))
    result.isLeft shouldBe true
    result.swap.toOption.get
      .asInstanceOf[chess.model.GameError]
      .message should include("Black")
