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
        html.contains(
          """<link rel="icon" type="image/png" sizes="32x32" href="/web/peach-32.png">"""
        ),
        html.contains(
          """<link rel="icon" type="image/png" sizes="192x192" href="/web/peach.png">"""
        ),
        html.contains(
          """<link rel="apple-touch-icon" sizes="180x180" href="/web/peach-180.png">"""
        )
      )
    },
    test("preconnect and load the scrapbook font stack from Google Fonts") {
      // display=swap avoids FOIT — fallback renders immediately and the
      // scrapbook fonts swap in once they arrive.
      assertTrue(
        html.contains(
          """<link rel="preconnect" href="https://fonts.googleapis.com">"""
        ),
        html.contains(
          """<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>"""
        ),
        html.contains("fonts.googleapis.com/css2"),
        html.contains("Caveat+Brush"),
        html.contains("family=Caveat"),
        html.contains("Special+Elite"),
        html.contains("display=swap")
      )
    },
    test("load overlayScrollbars CSS + JS from CDN, sync") {
      // Sync (no defer/async) so the global OverlayScrollbarsGlobal symbol
      // is available before /web/main.js boots and tries to call OS on
      // mount. Hand-drawn distortion filter is inline so OS-rendered
      // handles can reference filter: url(#hand-drawn).
      assertTrue(
        html.contains("overlayscrollbars@2"),
        html.contains("overlayscrollbars.min.css"),
        html.contains("overlayscrollbars.browser.es6.min.js"),
        html.contains("""id="hand-drawn"""")
      )
    },
    test("embed paper-SVG sprites for document-internal <use> references") {
      // Cross-document <use href="external.svg#id"/> has flaky CSS-variable
      // cascade through <pattern>/<feDiffuseLighting>; document-internal
      // <use href="#id"/> against inlined symbols cascades reliably. The
      // symbols are loaded from the resource files at build time and
      // embedded inside .svg-sprite-host so they're present in every
      // rendered page.
      assertTrue(
        html.contains("""class="svg-sprite-host""""),
        html.contains("""id="paper-crumpled-square""""),
        html.contains("""id="paper-crumpled-grid-square"""")
      )
    },
    test("embed all six piece-SVG sprites in the same sprite host") {
      // The floating drag clone mounts a fresh <use href="#<name>"/> on
      // every pointerdown. Cross-document references forced a per-mount
      // resolve step that visibly delayed drag start; inlining the
      // <symbol id="pawn">, <symbol id="rook">, etc. lets the same
      // reference resolve synchronously from the current document.
      assertTrue(
        html.contains("""id="pawn""""),
        html.contains("""id="rook""""),
        html.contains("""id="knight""""),
        html.contains("""id="bishop""""),
        html.contains("""id="queen""""),
        html.contains("""id="king"""")
      )
    },
    test("inline a synchronous theme-bootstrap script before paint") {
      // The Scala.js bundle loads after the body, so without a synchronous
      // <head> script the user briefly sees the wrong mode before the JS
      // runs (FOUT). The bootstrap reads localStorage and sets the `dark`
      // class on documentElement before the first paint so the chosen
      // theme applies immediately. Light is the default — only explicit
      // 'dark' opts in.
      val headEnd = html.indexOf("</head>")
      val bodyStart = html.indexOf("<body")
      val scriptIdx = html.indexOf("pichess.theme")
      assertTrue(
        scriptIdx > 0,
        scriptIdx < headEnd,
        headEnd < bodyStart,
        html.contains("localStorage.getItem('pichess.theme')"),
        html.contains("'dark'"),
        html.contains("documentElement.classList.add('dark')")
      )
    }
  )
