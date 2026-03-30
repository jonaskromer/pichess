package chess.model.rules

import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.{GameState, Move, Position}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GameSpec extends AnyFlatSpec with Matchers:

  private val initial = GameState.initial

  private lazy val enPassantState = GameState(
    Map(
      Position('e', 5) -> Piece(Color.White, PieceType.Pawn),
      Position('d', 5) -> Piece(Color.Black, PieceType.Pawn)
    ),
    Color.White,
    enPassantTarget = Some(Position('d', 6))
  )

  "Game.applyMove" should "move a piece from source to destination" in:
    val move = Move(Position('e', 2), Position('e', 4))
    val result = Game.applyMove(initial, move)
    result.isRight shouldBe true
    result.toOption.get.board(Position('e', 4)) shouldBe Piece(Color.White, PieceType.Pawn)

  it should "empty the source square after a move" in:
    val move = Move(Position('e', 2), Position('e', 4))
    val result = Game.applyMove(initial, move)
    result.toOption.get.board.get(Position('e', 2)) shouldBe None

  it should "switch the active color from White to Black after a White move" in:
    val move = Move(Position('e', 2), Position('e', 4))
    val result = Game.applyMove(initial, move)
    result.toOption.get.activeColor shouldBe Color.Black

  it should "switch the active color from Black to White after a Black move" in:
    val afterWhite = Game.applyMove(initial, Move(Position('e', 2), Position('e', 4))).toOption.get
    val move = Move(Position('e', 7), Position('e', 5))
    val result = Game.applyMove(afterWhite, move)
    result.toOption.get.activeColor shouldBe Color.White

  it should "capture an opponent piece by overwriting the destination" in:
    val state = GameState(
      Map(
        Position('d', 1) -> Piece(Color.White, PieceType.Queen),
        Position('g', 4) -> Piece(Color.Black, PieceType.Pawn)
      ),
      Color.White
    )
    val move = Move(Position('d', 1), Position('g', 4))
    val result = Game.applyMove(state, move)
    result.isRight shouldBe true
    result.toOption.get.board(Position('g', 4)) shouldBe Piece(Color.White, PieceType.Queen)

  it should "return Left when the source square is empty" in:
    val move = Move(Position('e', 4), Position('e', 5))
    val result = Game.applyMove(initial, move)
    result.isLeft shouldBe true
    result.left.get should include("e4")

  it should "return Left when moving an opponent's piece" in:
    val move = Move(Position('e', 7), Position('e', 5))
    val result = Game.applyMove(initial, move)
    result.isLeft shouldBe true
    result.left.get should include("Black")

  // ─── En passant target tracking ─────────────────────────────────────────────

  it should "set enPassantTarget after a white pawn double advance" in:
    val result = Game.applyMove(initial, Move(Position('e', 2), Position('e', 4)))
    result.toOption.get.enPassantTarget shouldBe Some(Position('e', 3))

  it should "set enPassantTarget after a black pawn double advance" in:
    val afterWhite = Game.applyMove(initial, Move(Position('e', 2), Position('e', 4))).toOption.get
    val result = Game.applyMove(afterWhite, Move(Position('d', 7), Position('d', 5)))
    result.toOption.get.enPassantTarget shouldBe Some(Position('d', 6))

  it should "clear enPassantTarget after a non-double-advance move" in:
    val withTarget = Game.applyMove(initial, Move(Position('e', 2), Position('e', 4))).toOption.get
    val result = Game.applyMove(withTarget, Move(Position('d', 7), Position('d', 6)))
    result.toOption.get.enPassantTarget shouldBe None

  it should "overwrite enPassantTarget when a second double advance follows the first" in:
    val afterWhite = Game.applyMove(initial, Move(Position('e', 2), Position('e', 4))).toOption.get
    afterWhite.enPassantTarget shouldBe Some(Position('e', 3))
    val afterBlack =
      Game.applyMove(afterWhite, Move(Position('d', 7), Position('d', 5))).toOption.get
    afterBlack.enPassantTarget shouldBe Some(Position('d', 6))

  it should "clear enPassantTarget after a single pawn advance" in:
    val withTarget = Game.applyMove(initial, Move(Position('e', 2), Position('e', 4))).toOption.get
    val result = Game.applyMove(withTarget, Move(Position('a', 7), Position('a', 6)))
    result.toOption.get.enPassantTarget shouldBe None

  // ─── En passant capture mechanics ───────────────────────────────────────────

  it should "allow en passant capture and place the pawn on the target square" in:
    val result = Game.applyMove(enPassantState, Move(Position('e', 5), Position('d', 6)))
    result.isRight shouldBe true
    result.toOption.get.board.get(Position('d', 6)) shouldBe Some(
      Piece(Color.White, PieceType.Pawn)
    )

  it should "remove the captured pawn after en passant" in:
    val result = Game.applyMove(enPassantState, Move(Position('e', 5), Position('d', 6)))
    result.toOption.get.board.get(Position('d', 5)) shouldBe None

  it should "clear enPassantTarget after an en passant capture" in:
    val result = Game.applyMove(enPassantState, Move(Position('e', 5), Position('d', 6)))
    result.toOption.get.enPassantTarget shouldBe None
