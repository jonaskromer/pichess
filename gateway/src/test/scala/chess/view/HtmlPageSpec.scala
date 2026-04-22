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
    test("mount Laminar at the #app element") {
      assertTrue(html.contains("""id="app""""))
    },
    test("load the Scala.js bundle") {
      assertTrue(html.contains("""<script src="/web/main.js"></script>"""))
    },
    test("inline the stylesheet") {
      assertTrue(
        html.contains("<style>"),
        html.contains("grid-template-columns: repeat(8,")
      )
    },
  )
