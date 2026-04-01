package chess.view

import zio.test.*

object HtmlPageSpec extends ZIOSpecDefault:

  private val html = HtmlPage.render

  def spec = suite("HtmlPage.render")(
    test("produce a valid HTML document") {
      assertTrue(
        html.contains("<!DOCTYPE html>"),
        html.contains("<html"),
        html.contains("</html>")
      )
    },
    test("include the page title") {
      assertTrue(html.contains("<title>piChess</title>"))
    },
    test("include CSS grid board styling") {
      assertTrue(
        html.contains("grid-template-columns: repeat(8,"),
        html.contains("grid-template-rows: repeat(8,")
      )
    },
    test("include light and dark square colors") {
      assertTrue(
        html.contains("#fde8d0"),
        html.contains("#e8956a")
      )
    },
    test("include the board container") {
      assertTrue(html.contains("""id="board""""))
    },
    test("include the move input form") {
      assertTrue(
        html.contains("""id="moveForm""""),
        html.contains("""id="moveInput"""")
      )
    },
    test("include drag and drop JavaScript handlers") {
      assertTrue(
        html.contains("dragstart"),
        html.contains("dragover"),
        html.contains("onDrop"),
        html.contains("onDragStart")
      )
    },
    test("include the move log container") {
      assertTrue(html.contains("""id="moveLog""""))
    },
    test("include the turn indicator") {
      assertTrue(html.contains("""id="turnIndicator""""))
    },
    test("include promotion dialog") {
      assertTrue(
        html.contains("""id="promotionOverlay""""),
        html.contains("""id="promotionDialog"""")
      )
    },
    test("include the new game button") {
      assertTrue(
        html.contains("newGame()"),
        html.contains("New Game")
      )
    },
    test("include the quit button") {
      assertTrue(
        html.contains("quitGame()"),
        html.contains("Quit")
      )
    },
    test("include the toast notification element") {
      assertTrue(html.contains("""id="toast""""))
    },
    test("include the fetch API call for moves") {
      assertTrue(html.contains("fetch('/api/move'"))
    },
    test("include the state loading function") {
      assertTrue(html.contains("fetch('/api/state'"))
    },
    test("include piece styling classes") {
      assertTrue(
        html.contains("white-piece"),
        html.contains("black-piece")
      )
    }
  )
