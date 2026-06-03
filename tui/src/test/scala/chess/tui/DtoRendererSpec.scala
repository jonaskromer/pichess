package chess.tui

import zio.test.*

import chess.api.{BoardStateDto, GameStatusDto, MoveEntryDto, SquareDto}

object DtoRendererSpec extends ZIOSpecDefault:

  /** Build a minimal BoardStateDto: every square occupied by a single
    * piece keyed off the test setup, no moves, no error.
    */
  private def board(
      squares: List[(String, Option[(String, String)])] = Nil,
      activeColor: String = "white",
      moveLog: List[MoveEntryDto] = Nil,
      error: Option[String] = None,
      inCheck: Boolean = false,
      checkedKingPos: Option[String] = None,
      status: GameStatusDto = GameStatusDto.Playing
  ): BoardStateDto =
    val all = (for
      col <- 'a' to 'h'
      row <- 1 to 8
    yield s"$col$row").toList

    val explicit = squares.toMap
    val sqs = all.map { pos =>
      val isDark = (pos.head - 'a' + (pos.last - '0')) % 2 == 1
      val color = if isDark then "dark" else "light"
      explicit.get(pos).flatten match
        case Some((piece, pieceColor)) =>
          SquareDto(pos, color, Some(piece), Some(pieceColor))
        case None =>
          SquareDto(pos, color, None, None)
    }
    BoardStateDto(sqs, activeColor, moveLog, error, inCheck, checkedKingPos, status)

  def spec = suite("DtoRenderer.render")(
    test("includes coordinate labels for files and ranks") {
      val out = DtoRenderer.render(board())
      assertTrue(
        out.contains(" a "),
        out.contains(" h "),
        out.contains("1") && out.contains("8")
      )
    },
    test("flipped renders ranks bottom-to-top from White's perspective") {
      val unflipped = DtoRenderer.render(board(), flipped = false)
      val flipped = DtoRenderer.render(board(), flipped = true)
      // Easy distinguishing check: in flipped output, the "1" rank labels
      // appear before the "8" rank labels, and vice versa for unflipped.
      assertTrue(
        unflipped.indexOf("8") < unflipped.indexOf("1"),
        flipped.indexOf("1") < flipped.indexOf("8")
      )
    },
    test("renders the white-to-move indicator") {
      val out = DtoRenderer.render(board(activeColor = "white"))
      assertTrue(out.contains("white to move"))
    },
    test("renders pieces with the matching unicode glyph") {
      val withKing = board(squares = List("e1" -> Some("king" -> "white")))
      val out = DtoRenderer.render(withKing)
      assertTrue(out.contains("♔")) // ♔
    },
    test("renders unknown piece names by their first letter") {
      val withWeird = board(squares = List("e1" -> Some("zebra" -> "white")))
      val out = DtoRenderer.render(withWeird)
      assertTrue(out.contains("Z"))
    },
    test("renders a move log as numbered pairs") {
      val log = List(
        MoveEntryDto("white", "e4"),
        MoveEntryDto("black", "e5"),
        MoveEntryDto("white", "Nf3")
      )
      val out = DtoRenderer.render(board(moveLog = log))
      assertTrue(
        out.contains("1. e4 e5"),
        out.contains("2. Nf3")
      )
    },
    test("omits the moves section when no moves have been played") {
      val out = DtoRenderer.render(board())
      assertTrue(!out.contains("Moves:"))
    },
    test("renders the playing status line") {
      val out = DtoRenderer.render(board())
      assertTrue(out.contains("Game in progress"))
    },
    test("renders checkmate with the winner") {
      val out = DtoRenderer.render(
        board(status = GameStatusDto.checkmate("white"))
      )
      assertTrue(out.contains("CHECKMATE"), out.contains("white"))
    },
    test("renders draw with the reason") {
      val out = DtoRenderer.render(
        board(status = GameStatusDto.draw("FiftyMoveRule"))
      )
      assertTrue(out.contains("DRAW"), out.contains("FiftyMoveRule"))
    },
    test("renders resignation with the surviving winner") {
      val out = DtoRenderer.render(
        board(status = GameStatusDto.resignation("black"))
      )
      assertTrue(out.contains("RESIGNATION"), out.contains("black"))
    },
    test("renders unknown status kinds with their raw label") {
      val out = DtoRenderer.render(
        board(status = GameStatusDto("paused", None, None))
      )
      assertTrue(out.contains("paused"))
    },
    test("surfaces an error message when the DTO carries one") {
      val out = DtoRenderer.render(board(error = Some("invalid move")))
      assertTrue(out.contains("Error: invalid move"))
    },
    test("highlights the checked king's square") {
      val out = DtoRenderer.render(
        board(
          squares = List("e1" -> Some("king" -> "white")),
          inCheck = true,
          checkedKingPos = Some("e1")
        )
      )
      // Check that the check-color escape sequence appears somewhere.
      assertTrue(out.contains("[1;91m"))
    },
    test("renders black pieces using the black-piece glyph table") {
      val out = DtoRenderer.render(
        board(squares = List("e8" -> Some("queen" -> "black")))
      )
      assertTrue(out.contains("♛")) // ♛
    },
    test("renders empty cells when the DTO omits some squares") {
      // Construct a sparse DTO: only e1 is described; the other 63 squares
      // exercise the byPos.get == None branch in renderBoard.
      val sparse = BoardStateDto(
        squares = List(SquareDto("e1", "light", Some("king"), Some("white"))),
        activeColor = "white",
        moveLog = Nil,
        error = None,
        inCheck = false,
        checkedKingPos = None,
        status = GameStatusDto.Playing
      )
      val out = DtoRenderer.render(sparse)
      assertTrue(
        out.contains("♔"),                       // the one square we described
        out.split('\n').count(_.contains("8")) >= 1 // top rank still labelled
      )
    }
  )
