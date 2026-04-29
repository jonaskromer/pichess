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
    test("link rasterised peach favicons in three sizes") {
      // PNG (not SVG) because Safari's icon parser silently fails on SVGs
      // that use <symbol>+<use> indirection. Three sizes — 32×32 for the
      // tab, 192×192 for hi-DPI / Android, 180×180 for the apple-touch-icon.
      assertTrue(
        html.contains("""<link rel="icon" type="image/png" sizes="32x32" href="/web/peach-32.png">"""),
        html.contains("""<link rel="icon" type="image/png" sizes="192x192" href="/web/peach.png">"""),
        html.contains("""<link rel="apple-touch-icon" sizes="180x180" href="/web/peach-180.png">"""),
      )
    },
  )
