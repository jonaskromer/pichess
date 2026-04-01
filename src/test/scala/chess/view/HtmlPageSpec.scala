package chess.view

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HtmlPageSpec extends AnyFlatSpec with Matchers:

  private val html = HtmlPage.render

  "HtmlPage.render" should "produce a valid HTML document" in:
    html should include("<!DOCTYPE html>")
    html should include("<html")
    html should include("</html>")

  it should "include the page title" in:
    html should include("<title>piChess</title>")

  it should "include CSS grid board styling" in:
    html should include("grid-template-columns: repeat(8,")
    html should include("grid-template-rows: repeat(8,")

  it should "include light and dark square colors" in:
    html should include("#fde8d0")
    html should include("#e8956a")

  it should "include the board container" in:
    html should include("""id="board"""")

  it should "include the move input form" in:
    html should include("""id="moveForm"""")
    html should include("""id="moveInput"""")

  it should "include drag and drop JavaScript handlers" in:
    html should include("dragstart")
    html should include("dragover")
    html should include("onDrop")
    html should include("onDragStart")

  it should "include the move log container" in:
    html should include("""id="moveLog"""")

  it should "include the turn indicator" in:
    html should include("""id="turnIndicator"""")

  it should "include promotion dialog" in:
    html should include("""id="promotionOverlay"""")
    html should include("""id="promotionDialog"""")

  it should "include the new game button" in:
    html should include("newGame()")
    html should include("New Game")

  it should "include the quit button" in:
    html should include("quitGame()")
    html should include("Quit")

  it should "include the toast notification element" in:
    html should include("""id="toast"""")

  it should "include the fetch API call for moves" in:
    html should include("fetch('/api/move'")

  it should "include the state loading function" in:
    html should include("fetch('/api/state'")

  it should "include piece styling classes" in:
    html should include("white-piece")
    html should include("black-piece")
