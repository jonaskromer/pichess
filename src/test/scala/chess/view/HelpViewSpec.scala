package chess.view

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HelpViewSpec extends AnyFlatSpec with Matchers:

  private val help = HelpView.render

  "HelpView.render" should "list the move command" in:
    help should include("<from> <to>")

  it should "list the help command" in:
    help should include("help")

  it should "list the flip command" in:
    help should include("flip")

  it should "list the quit command" in:
    help should include("quit")

  it should "explain move notation columns" in:
    help should include("a")
    help should include("h")

  it should "explain move notation rows" in:
    help should include("1")
    help should include("8")

  it should "include a move example" in:
    help should include("e2 e4")

  it should "list pawn as implemented" in:
    help should include("Pawn")

  it should "list rook as implemented" in:
    help should include("Rook")

  it should "list bishop as implemented" in:
    help should include("Bishop")

  it should "list queen as implemented" in:
    help should include("Queen")

  it should "list knight as implemented" in:
    help should include("Knight")

  it should "list king as implemented" in:
    help should include("King")

  it should "mention en passant as implemented" in:
    help should include("en passant")

  it should "list castling as not implemented" in:
    help should include("Castling")

  it should "list pawn promotion as not implemented" in:
    help should include("Pawn promotion")

  it should "list check detection as not implemented" in:
    help should include("Check detection")

  it should "list checkmate as not implemented" in:
    help should include("Checkmate")

  it should "list stalemate as not implemented" in:
    help should include("Stalemate")

  it should "list draw conditions as not implemented" in:
    help should include("Draw conditions")
