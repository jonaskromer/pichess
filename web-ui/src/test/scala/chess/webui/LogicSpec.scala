package chess.webui

import chess.api.{BoardStateDto, GameStatusDto, MoveEntryDto, SquareDto}
import zio.test.*

object LogicSpec extends ZIOSpecDefault:

  // Minimal state factory — `isPawnPromotion` only inspects `squares`, so
  // the other BoardStateDto fields are placeholders.
  private def stateWith(squares: SquareDto*): BoardStateDto =
    BoardStateDto(
      squares        = squares.toList,
      activeColor    = "white",
      moveLog        = Nil,
      error          = None,
      inCheck        = false,
      checkedKingPos = None,
      status         = GameStatusDto.Playing,
    )

  private val whitePawnOnE7 =
    SquareDto("e7", "dark", Some("♙"), Some("white"))
  private val blackPawnOnE2 =
    SquareDto("e2", "light", Some("♟"), Some("black"))
  private val whiteRookOnE4 =
    SquareDto("e4", "light", Some("♖"), Some("white"))
  private val emptyE7 =
    SquareDto("e7", "dark", None, None)

  def spec = suite("Logic")(
    suite("isPawnPromotion")(
      test("true when a white pawn moves to rank 8") {
        val s = stateWith(whitePawnOnE7)
        assertTrue(Logic.isPawnPromotion("e7", "e8", s))
      },
      test("true when a black pawn moves to rank 1") {
        val s = stateWith(blackPawnOnE2)
        assertTrue(Logic.isPawnPromotion("e2", "e1", s))
      },
      test("true on a diagonal promotion capture") {
        val s = stateWith(whitePawnOnE7)
        assertTrue(Logic.isPawnPromotion("e7", "d8", s))
      },
      test("false when the moving piece is not a pawn") {
        val s = stateWith(whiteRookOnE4)
        assertTrue(!Logic.isPawnPromotion("e4", "e8", s))
      },
      test("false when the destination rank is not the back rank") {
        val s = stateWith(whitePawnOnE7)
        assertTrue(!Logic.isPawnPromotion("e7", "e6", s))
      },
      test("false when the source square is empty") {
        val s = stateWith(emptyE7)
        assertTrue(!Logic.isPawnPromotion("e7", "e8", s))
      },
      test("false when the source square isn't in the state at all") {
        val s = stateWith()
        assertTrue(!Logic.isPawnPromotion("e7", "e8", s))
      },
    ),
    suite("groupMovesByTwo")(
      test("empty input yields empty output") {
        assertTrue(Logic.groupMovesByTwo(Nil).isEmpty)
      },
      test("single white move yields one row with no black entry") {
        val result = Logic.groupMovesByTwo(
          List(MoveEntryDto("white", "e4"))
        )
        assertTrue(
          result.size == 1,
          result.head == (1, MoveEntryDto("white", "e4"), None),
        )
      },
      test("four moves yield two rows with correct numbering") {
        val moves = List(
          MoveEntryDto("white", "e4"),
          MoveEntryDto("black", "e5"),
          MoveEntryDto("white", "Nf3"),
          MoveEntryDto("black", "Nc6"),
        )
        val result = Logic.groupMovesByTwo(moves)
        assertTrue(
          result == List(
            (1, MoveEntryDto("white", "e4"),  Some(MoveEntryDto("black", "e5"))),
            (2, MoveEntryDto("white", "Nf3"), Some(MoveEntryDto("black", "Nc6"))),
          )
        )
      },
      test("five moves: two full rows plus a dangling white") {
        val moves = List(
          MoveEntryDto("white", "e4"),
          MoveEntryDto("black", "e5"),
          MoveEntryDto("white", "Nf3"),
          MoveEntryDto("black", "Nc6"),
          MoveEntryDto("white", "Bb5"),
        )
        val result = Logic.groupMovesByTwo(moves)
        assertTrue(
          result.size == 3,
          result(2) == (3, MoveEntryDto("white", "Bb5"), None),
        )
      },
    ),
    suite("selectPromotionPieces")(
      test("white returns the four uppercase glyphs") {
        val keys = Logic.selectPromotionPieces(true).map(_._1)
        val glyphs = Logic.selectPromotionPieces(true).map(_._2)
        assertTrue(
          keys   == List("Q", "R", "B", "N"),
          glyphs == List("♕", "♖", "♗", "♘"),
        )
      },
      test("black returns the four lowercase glyphs in the same order") {
        val keys = Logic.selectPromotionPieces(false).map(_._1)
        val glyphs = Logic.selectPromotionPieces(false).map(_._2)
        assertTrue(
          keys   == List("Q", "R", "B", "N"),
          glyphs == List("♛", "♜", "♝", "♞"),
        )
      },
      test("all four promotion keys are distinct") {
        assertTrue(
          Logic.selectPromotionPieces(true).map(_._1).distinct.size == 4
        )
      },
    ),
  )
