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
    suite("humanizeDrawReason")(
      test("known reasons render as friendly phrases") {
        assertTrue(
          Logic.humanizeDrawReason("fiftyMoveRule") == "50-move rule",
          Logic.humanizeDrawReason("threefoldRepetition") == "threefold repetition",
          Logic.humanizeDrawReason("fivefoldRepetition") == "fivefold repetition",
          Logic.humanizeDrawReason("stalemate") == "stalemate",
          Logic.humanizeDrawReason("insufficientMaterial") == "insufficient material",
        )
      },
      test("unknown reasons fall through unchanged") {
        assertTrue(Logic.humanizeDrawReason("agreement") == "agreement")
      },
    ),
    suite("isWhiteGlyph")(
      test("returns true for white glyphs") {
        assertTrue(
          Logic.isWhiteGlyph("♔"),
          Logic.isWhiteGlyph("♕"),
          Logic.isWhiteGlyph("♖"),
          Logic.isWhiteGlyph("♗"),
          Logic.isWhiteGlyph("♘"),
          Logic.isWhiteGlyph("♙"),
        )
      },
      test("returns false for black glyphs and unknowns") {
        assertTrue(
          !Logic.isWhiteGlyph("♚"),
          !Logic.isWhiteGlyph("♟"),
          !Logic.isWhiteGlyph("?"),
        )
      },
    ),
    suite("capturedFromSquares")(
      test("starting position has no captures") {
        val (white, black) = Logic.capturedFromSquares(startingSquares)
        assertTrue(white.isEmpty, black.isEmpty)
      },
      test("removing a black pawn shows up in blackLost") {
        val squares = startingSquares.map { sq =>
          if sq.pos == "e7" then sq.copy(piece = None, pieceColor = None)
          else sq
        }
        val (white, black) = Logic.capturedFromSquares(squares)
        assertTrue(white.isEmpty, black == List("♟"))
      },
      test("multiple captures sort by descending value") {
        // Remove black queen, a black rook, and two black pawns.
        val removed = Set("d8", "a8", "a7", "b7")
        val squares = startingSquares.map { sq =>
          if removed.contains(sq.pos) then sq.copy(piece = None, pieceColor = None)
          else sq
        }
        val (_, black) = Logic.capturedFromSquares(squares)
        assertTrue(black == List("♛", "♜", "♟", "♟"))
      },
      test("under-promotion to a second queen still treats the lost pawn as captured") {
        // Move the d8 black queen square away and add a second white queen
        // somewhere, simulating a promoted-pawn position. The promoted
        // white pawn shows as a captured ♙ since it's missing from the
        // starting count.
        val squares = startingSquares.map { sq =>
          if sq.pos == "e2" then sq.copy(piece = Some("♕"), pieceColor = Some("white"))
          else sq
        }
        val (white, _) = Logic.capturedFromSquares(squares)
        assertTrue(white == List("♙"))
      },
      test("foreign glyphs in squares are ignored") {
        val squares = startingSquares :+ SquareDto("z9", "light", Some("?"), Some("white"))
        val (white, black) = Logic.capturedFromSquares(squares)
        assertTrue(white.isEmpty, black.isEmpty)
      },
    ),
  )

  // Build a starting-position square list using the same conventions as
  // WebBoardView (lowercase-letter glyphs for black, uppercase for white).
  private val startingSquares: List[SquareDto] =
    val whiteBack = List("♖","♘","♗","♕","♔","♗","♘","♖")
    val blackBack = List("♜","♞","♝","♛","♚","♝","♞","♜")
    (for
      rank <- (8 to 1 by -1).toList
      file <- ('a' to 'h').toList
    yield
      val pos = s"$file$rank"
      val (piece, color) = rank match
        case 8 => (Some(blackBack(file - 'a')), Some("black"))
        case 7 => (Some("♟"),                   Some("black"))
        case 2 => (Some("♙"),                   Some("white"))
        case 1 => (Some(whiteBack(file - 'a')), Some("white"))
        case _ => (None, None)
      val sqColor = if (file - 'a' + rank) % 2 == 0 then "dark" else "light"
      SquareDto(pos, sqColor, piece, color)
    )
