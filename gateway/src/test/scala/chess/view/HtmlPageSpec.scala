package chess.view

import zio.test.*

object HtmlPageSpec extends ZIOSpecDefault:

  private val html = HtmlPage.render()

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
      // Cache-busted with a content-hash query (?v=…) so a new build is fetched
      // on reload; match the stable prefix, not the per-build hash.
      assertTrue(html.contains("""<script src="/web/main.js?v="""))
    },
    test("inline the stylesheet") {
      // The stylesheet ships minified via the Tailwind pipeline, so the
      // assertion has to be space-tolerant. `repeat(8,` survives the
      // minifier (no space after the comma) and is unique enough to be
      // a good "is the chess-board grid actually in here" probe.
      assertTrue(
        html.contains("<style>"),
        html.contains("repeat(8,")
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
    test("load the scrapbook font stack from the local vendored stylesheet") {
      // Vendored by `make web-vendor` into /web/vendor/fonts — no CDN, no
      // Google Fonts preconnect (the @font-face rules live in fonts.css).
      assertTrue(
        html.contains(
          """<link rel="stylesheet" href="/web/vendor/fonts/fonts.css">"""
        ),
        !html.contains("fonts.googleapis.com"),
        !html.contains("fonts.gstatic.com")
      )
    },
    test("load overlayScrollbars CSS + JS from the local vendor dir, sync") {
      // Vendored locally (make web-vendor) — no CDN. Sync (no defer/async) so
      // the global OverlayScrollbarsGlobal symbol is available before
      // /web/main.js boots and calls OS on mount. Hand-drawn distortion filter
      // is inline so OS-rendered handles can reference filter: url(#hand-drawn).
      assertTrue(
        html.contains("/web/vendor/overlayscrollbars/overlayscrollbars.min.css"),
        html.contains(
          "/web/vendor/overlayscrollbars/overlayscrollbars.browser.es6.min.js"
        ),
        !html.contains("overlayscrollbars@2"),
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
        // Grid-only paper sprite now (the crumple texture moved to the baked
        // #crumple-tile heightmap + the per-theme #crumple-hard/-soft lighting
        // filters, all inlined here for the document-internal var cascade).
        html.contains("""id="paper-grid-square""""),
        html.contains("""id="crumple-tile""""),
        html.contains("""id="crumple-hard"""")
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
    },
    test("inject the obs UI base URLs as meta tags (dev-default fallback)") {
      assertTrue(
        html.contains(
          """<meta name="pichess-grafana" content="http://localhost:3000">"""
        ),
        html.contains(
          """<meta name="pichess-prometheus" content="http://localhost:9090">"""
        )
      )
    },
    test("inject custom obs URLs when the gateway supplies them") {
      val custom = HtmlPage.render(
        grafanaUrl = "http://grafana.141.37.123.131.nip.io",
        prometheusUrl = "http://prometheus.141.37.123.131.nip.io"
      )
      assertTrue(
        custom.contains(
          """<meta name="pichess-grafana" content="http://grafana.141.37.123.131.nip.io">"""
        ),
        custom.contains(
          """<meta name="pichess-prometheus" content="http://prometheus.141.37.123.131.nip.io">"""
        )
      )
    }
  )
