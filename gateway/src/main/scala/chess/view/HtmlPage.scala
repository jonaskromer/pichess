package chess.view

import scala.io.Source

object HtmlPage:

  /** Render the SPA shell. `devMode` controls whether the web-ui surfaces the
    * /dev link on the start screen + activates the #dev/… hash routes. The flag
    * is read by the SPA from the `<meta name="pichess-dev">` tag injected here.
    */
  def render(
      devMode: Boolean = false,
      lichessEnabled: Boolean = false,
      grafanaUrl: String = "http://localhost:3000",
      prometheusUrl: String = "http://localhost:9090"
  ): String =
    val devMeta =
      s"""<meta name="pichess-dev" content="${devMode.toString}">"""
    val lichessMeta =
      s"""<meta name="pichess-lichess" content="${lichessEnabled.toString}">"""
    val grafanaMeta =
      s"""<meta name="pichess-grafana" content="$grafanaUrl">"""
    val prometheusMeta =
      s"""<meta name="pichess-prometheus" content="$prometheusUrl">"""
    s"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
$devMeta
$lichessMeta
$grafanaMeta
$prometheusMeta
<title>piChess</title>
<link rel="icon" type="image/png" sizes="32x32" href="/web/peach-32.png">
<link rel="icon" type="image/png" sizes="192x192" href="/web/peach.png">
<link rel="apple-touch-icon" sizes="180x180" href="/web/peach-180.png">
<!-- Self-hosted scrapbook fonts (Caveat Brush, Caveat 400/700, Special
     Elite) — vendored by `make web-vendor` into /web/vendor/fonts. No CDN. -->
<link rel="stylesheet" href="/web/vendor/fonts/fonts.css">
<!-- overlayScrollbars 2.10.0 — vendored locally (make web-vendor), used to
     skin .move-log's scrollbar with a hand-drawn hatched look (native
     ::-webkit-scrollbar can't accept the filter:url(#hand-drawn) effect on the
     handle the way OS's DOM-rendered handle can). Loaded sync so the global
     OverlayScrollbarsGlobal symbol is available before /web/main.js calls into
     it on mount; the init is guarded so a missing global degrades to native. -->
<link rel="stylesheet" href="/web/vendor/overlayscrollbars/overlayscrollbars.min.css">
<script src="/web/vendor/overlayscrollbars/overlayscrollbars.browser.es6.min.js"></script>
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
<!-- Crumpled-paper pipeline. The costly live feTurbulence is baked once into
     crumple-height.png; the `#crumple-tile` pattern tiles it at a FIXED 640px
     (= 8 folds, so crumple size is constant regardless of panel size). The two
     filters light that heightmap LIVE so rotation stays world-fixed (azimuth set
     per element) and the paper retints via --crumple-highlight / --paper-color:
     hard-light for light mode, soft-light for dark (multiply only darkens, which
     reads as stains on dark paper). Chosen per theme via `filter: var(--paper-filter)`.
     `luminanceToAlpha` turns the grayscale tile into the alpha bump feDiffuseLighting
     reads; the feComponentTransfer recentres the lighting so a flat face → 0.5
     (ridges lighten, creases darken). -->
<svg width="0" height="0" aria-hidden="true">
  <defs>
    <pattern id="crumple-tile" width="640" height="640" patternUnits="userSpaceOnUse">
      <image href="/web/crumple-height.png" width="640" height="640"/>
    </pattern>
    <filter id="crumple-hard" x="0" y="0" width="100%" height="100%" primitiveUnits="userSpaceOnUse">
      <feColorMatrix in="SourceGraphic" type="luminanceToAlpha" result="hmap"/>
      <feDiffuseLighting in="hmap" surfaceScale="3" diffuseConstant="1"
                         lighting-color="#fff" style="lighting-color: var(--crumple-highlight, #fff)" result="lightRaw">
        <feDistantLight azimuth="135" elevation="45"/>
      </feDiffuseLighting>
      <feComponentTransfer in="lightRaw" result="lightSoft">
        <feFuncR type="linear" slope="0.65" intercept="0.04"/>
        <feFuncG type="linear" slope="0.65" intercept="0.04"/>
        <feFuncB type="linear" slope="0.65" intercept="0.04"/>
      </feComponentTransfer>
      <feFlood flood-color="#fdfbf3" style="flood-color: var(--paper-color, #fdfbf3)" result="paper"/>
      <feBlend in="lightSoft" in2="paper" mode="hard-light"/>
    </filter>
    <filter id="crumple-soft" x="0" y="0" width="100%" height="100%" primitiveUnits="userSpaceOnUse">
      <feColorMatrix in="SourceGraphic" type="luminanceToAlpha" result="hmap"/>
      <feDiffuseLighting in="hmap" surfaceScale="3" diffuseConstant="1"
                         lighting-color="#fff" style="lighting-color: var(--crumple-highlight, #fff)" result="lightRaw">
        <feDistantLight azimuth="135" elevation="35"/>
      </feDiffuseLighting>
      <feComponentTransfer in="lightRaw" result="lightSoft">
        <feFuncR type="linear" slope="0.60" intercept="0.156"/>
        <feFuncG type="linear" slope="0.60" intercept="0.156"/>
        <feFuncB type="linear" slope="0.60" intercept="0.156"/>
      </feComponentTransfer>
      <feFlood flood-color="#fdfbf3" style="flood-color: var(--paper-color, #fdfbf3)" result="paper"/>
      <feBlend in="lightSoft" in2="paper" mode="soft-light"/>
    </filter>
  </defs>
</svg>
</div>
<div id="app"></div>
<script src="/web/main.js?v=$mainJsVersion"></script>
</body>
</html>"""

  private val css: String = loadResource("web/style.css")
  // Cache-bust the Scala.js bundle: a content hash in the query string, so every
  // new build is fetched on reload even though the filename is fixed (`main.js`).
  // The CSS is inlined above, so it's always fresh with the rendered HTML — only
  // main.js is a separately-cacheable resource that needs this.
  private val mainJsVersion: String =
    Integer.toHexString(loadResource("web/main.js").hashCode)
  // Only the grid-only sprite is inlined now. The old gridless crumple SVG is
  // dead (every cutting + the page background moved to the baked #crumple-tile /
  // crumple-height.png pipeline), and inlining it ran a stray feTurbulence in
  // the 0×0 sprite host for nothing.
  private val paperSprites: String =
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
