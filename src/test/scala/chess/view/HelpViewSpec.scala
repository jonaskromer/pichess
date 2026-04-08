package chess.view

import zio.test.*

object HelpViewSpec extends ZIOSpecDefault:

  private val help = HelpView.render

  private val commandsSection =
    help.substring(help.indexOf("COMMANDS"), help.indexOf("\nFEN"))
  private val fenSection =
    help.substring(help.indexOf("\nFEN"), help.indexOf("MOVE NOTATION"))
  private val notationSection =
    help.substring(
      help.indexOf("MOVE NOTATION"),
      help.indexOf("IMPLEMENTED RULES")
    )
  private val implementedSection =
    help.substring(
      help.indexOf("IMPLEMENTED RULES"),
      help.indexOf("NOT YET IMPLEMENTED")
    )
  private val notYetSection =
    help.substring(help.indexOf("NOT YET IMPLEMENTED"))

  def spec = suite("HelpView.render")(
    suite("commands")(
      test("list the move command") {
        assertTrue(commandsSection.contains("<from> <to>"))
      },
      test("list the help command") {
        assertTrue(commandsSection.contains("help"))
      },
      test("list the flip command") {
        assertTrue(commandsSection.contains("flip"))
      },
      test("list the quit command") {
        assertTrue(commandsSection.contains("quit"))
      },
      test("list the fen command") {
        assertTrue(commandsSection.contains("fen"))
      }
    ),
    suite("FEN notation")(
      test("explain FEN format fields") {
        assertTrue(
          fenSection.contains("Placement"),
          fenSection.contains("Active"),
          fenSection.contains("Castling"),
          fenSection.contains("En passant"),
          fenSection.contains("Halfmove"),
          fenSection.contains("Fullmove")
        )
      },
      test("include an example FEN string") {
        assertTrue(fenSection.contains("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR"))
      },
      test("explain piece letter casing") {
        assertTrue(
          fenSection.contains("Uppercase = White"),
          fenSection.contains("lowercase = Black")
        )
      }
    ),
    suite("move notation")(
      test("mention Standard Algebraic Notation") {
        assertTrue(notationSection.contains("Standard Algebraic Notation"))
      },
      test("include a SAN piece move example") {
        assertTrue(notationSection.contains("Nf3"))
      },
      test("include a coordinate example") {
        assertTrue(notationSection.contains("e2 e4"))
      },
      test("explain column range") {
        assertTrue(
          notationSection.contains("a") && notationSection.contains("h")
        )
      },
      test("explain row range") {
        assertTrue(
          notationSection.contains("1") && notationSection.contains("8")
        )
      },
      test("list pawn promotion notation") {
        assertTrue(notationSection.contains("e8=Q"))
      },
      test("list castling notation") {
        assertTrue(
          notationSection.contains("O-O (kingside)"),
          notationSection.contains("O-O-O (queenside)")
        )
      },
      test("list piece letters") {
        assertTrue(
          notationSection.contains("N=Knight"),
          notationSection.contains("Q=Queen"),
          notationSection.contains("K=King")
        )
      }
    ),
    suite("implemented rules")(
      test("list pawn with movement rules") {
        assertTrue(
          implementedSection.contains("Pawn"),
          implementedSection.contains("en passant"),
          implementedSection.contains("promotion")
        )
      },
      test("list rook") {
        assertTrue(implementedSection.contains("Rook"))
      },
      test("list bishop") {
        assertTrue(implementedSection.contains("Bishop"))
      },
      test("list queen") {
        assertTrue(implementedSection.contains("Queen"))
      },
      test("list knight") {
        assertTrue(implementedSection.contains("Knight"))
      },
      test("list king with castling") {
        assertTrue(
          implementedSection.contains("King"),
          implementedSection.contains("O-O")
        )
      },
      test("list check as implemented") {
        assertTrue(implementedSection.contains("Check"))
      },
      test("list castling as implemented") {
        assertTrue(
          implementedSection.contains("Castling"),
          implementedSection.contains("rook jumps over")
        )
      },
      test("castling is not in the not-yet-implemented section") {
        assertTrue(!notYetSection.contains("Castling"))
      },
      test("check is not in the not-yet-implemented section") {
        assertTrue(!notYetSection.contains("Check "))
      },
      test("list checkmate as implemented") {
        assertTrue(implementedSection.contains("Checkmate"))
      },
      test("checkmate is not in the not-yet-implemented section") {
        assertTrue(!notYetSection.contains("Checkmate"))
      }
    ),
    suite("not yet implemented")(
      test("list stalemate") {
        assertTrue(notYetSection.contains("Stalemate"))
      },
      test("list draw conditions") {
        assertTrue(notYetSection.contains("Draw conditions"))
      }
    )
  )
