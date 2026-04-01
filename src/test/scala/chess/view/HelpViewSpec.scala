package chess.view

import zio.test.*

object HelpViewSpec extends ZIOSpecDefault:

  private val help = HelpView.render

  def spec = suite("HelpView.render")(
    test("list the move command") {
      assertTrue(help.contains("<from> <to>"))
    },
    test("list the help command") {
      assertTrue(help.contains("help"))
    },
    test("list the flip command") {
      assertTrue(help.contains("flip"))
    },
    test("list the quit command") {
      assertTrue(help.contains("quit"))
    },
    test("explain move notation columns") {
      assertTrue(help.contains("a"), help.contains("h"))
    },
    test("explain move notation rows") {
      assertTrue(help.contains("1"), help.contains("8"))
    },
    test("mention Standard Algebraic Notation") {
      assertTrue(help.contains("Algebraic Notation"))
    },
    test("include a SAN piece move example") {
      assertTrue(help.contains("Nf3"))
    },
    test("include a coordinate example") {
      assertTrue(help.contains("e2 e4"))
    },
    test("list pawn as implemented") {
      assertTrue(help.contains("Pawn"))
    },
    test("list rook as implemented") {
      assertTrue(help.contains("Rook"))
    },
    test("list bishop as implemented") {
      assertTrue(help.contains("Bishop"))
    },
    test("list queen as implemented") {
      assertTrue(help.contains("Queen"))
    },
    test("list knight as implemented") {
      assertTrue(help.contains("Knight"))
    },
    test("list king as implemented") {
      assertTrue(help.contains("King"))
    },
    test("mention en passant as implemented") {
      assertTrue(help.contains("en passant"))
    },
    test("list castling as not implemented") {
      assertTrue(help.contains("Castling"))
    },
    test("list pawn promotion in the notation section") {
      assertTrue(help.contains("Promotion"))
    },
    test("list check as implemented") {
      assertTrue(help.contains("Check"))
    },
    test("list checkmate as not implemented") {
      assertTrue(help.contains("Checkmate"))
    },
    test("list stalemate as not implemented") {
      assertTrue(help.contains("Stalemate"))
    },
    test("list draw conditions as not implemented") {
      assertTrue(help.contains("Draw conditions"))
    }
  )
