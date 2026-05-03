package chess.view

import scala.io.Source

object HtmlPage:

  def render: String =
    s"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>piChess</title>
<link rel="icon" type="image/png" sizes="32x32" href="/web/peach-32.png">
<link rel="icon" type="image/png" sizes="192x192" href="/web/peach.png">
<link rel="apple-touch-icon" sizes="180x180" href="/web/peach-180.png">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Caveat+Brush&amp;family=Caveat:wght@400;700&amp;family=Special+Elite&amp;display=swap">
<!-- overlayScrollbars 2.x — used to skin .move-log's scrollbar with a
     hand-drawn hatched look (native ::-webkit-scrollbar can't accept the
     filter:url(#hand-drawn) effect on the handle the way OS's DOM-rendered
     handle can). Loaded sync so the global OverlayScrollbarsGlobal symbol
     is available before /web/main.js calls into it on mount. -->
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/overlayscrollbars@2.10.0/styles/overlayscrollbars.min.css">
<script src="https://cdn.jsdelivr.net/npm/overlayscrollbars@2.10.0/browser/overlayscrollbars.browser.es6.min.js"></script>
<script>
// Theme bootstrap — runs synchronously in head, before the page paints,
// so the chosen mode applies immediately (no FOUT). The Scala.js bundle
// re-reads the same localStorage key on mount and keeps it in sync.
// Light is the default; only an explicit stored 'dark' opts in (the OS
// prefers-color-scheme is intentionally ignored here so the page palette
// stays consistent for first-time visitors).
(function(){
  if (localStorage.getItem('pichess.theme') === 'dark') {
    document.documentElement.classList.add('dark');
  }
})();
</script>
<style>
$css
</style>
</head>
<body>
<!-- Inline paper-SVG sprites. Symbols defined here are looked up by id
     from <use href="#paper-...-square"/> via document-internal references,
     which makes the CSS variable cascade for --paper-color / --grid-color
     / --crumple-highlight reliable into <pattern> and <feDiffuseLighting>
     (cross-document <use href="external.svg#id"> has flaky cascade for
     vars in those nested places — the proximate cause of the reported
     "grid colour doesn't change" bug). The SVG files at /web/...svg are
     kept as standalone resources but the page now renders from this
     embedded copy. -->
<div class="svg-sprite-host" aria-hidden="true">
$paperSprites
$pieceSprites
<!-- Hand-drawn distortion filter applied to scrollbar handle (and other
     things later). feTurbulence + feDisplacementMap warp the rendered
     alpha so straight rectangles become slightly wonky — sells the
     marker-on-paper feel. -->
<svg>
  <defs>
    <filter id="hand-drawn" x="-3%" y="-3%" width="106%" height="106%">
      <feTurbulence type="fractalNoise" baseFrequency="0.04"
                    numOctaves="2" seed="5" result="noise"/>
      <feDisplacementMap in="SourceGraphic" in2="noise" scale="2.5"
                         xChannelSelector="R" yChannelSelector="G"/>
    </filter>
  </defs>
</svg>
</div>
<div id="app"></div>
<script src="/web/main.js"></script>
</body>
</html>"""

  private val css: String = loadResource("web/style.css")
  private val paperSprites: String =
    loadResource("web/notebook-page-crumpled-square.svg") +
      loadResource("web/notebook-page-crumpled-grid-square.svg")
  // Piece SVGs were previously referenced cross-document via
  // `<use href="/web/pieces/<name>.svg#<name>"/>`. Even with a warm
  // browser cache that path forces a per-element resolve step the first
  // time each piece is mounted in a new context — most painfully when
  // the floating drag clone mounts on pointerdown, where the SVG had to
  // be (re-)resolved before the piece appeared. Inlining the symbol
  // definitions here makes `<use href="#<name>"/>` resolve from the
  // current document with no fetch and no parse delay, so drag start
  // is a single composite flip. The standalone /web/pieces/<name>.svg
  // route is unchanged — the file is still served for callers that
  // want a piece glyph as an asset (OG images, sharing, etc.).
  private val pieceSprites: String =
    List("pawn", "rook", "knight", "bishop", "queen", "king")
      .map(n => loadResource(s"web/pieces/$n.svg"))
      .mkString

  private def loadResource(path: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(path)
    val source = Source.fromInputStream(stream)
    try source.mkString
    finally source.close()
