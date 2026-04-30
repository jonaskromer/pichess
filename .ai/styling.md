# Web UI Styling — Working Notes

Pickup notes for continuing the πChess scrapbook-styling work in a new
session. Pair this with `.ai/BRIEFING.md` for general project rules
(strict TDD, 100% gateway coverage, `sbt scalafmtAll`, `regression:` test
discipline, etc.).

---

## Where we are

The UI is being styled as a **scrapbook**: paper backgrounds with crumple
shading, post-it buttons, marker highlights behind headings, newspaper
cutout buttons for navigation, adhesive tape on panels. Everything is
driven by CSS custom properties on `:root`, with `:root.dark` overriding
the same tokens — a single class flip on `<html>` re-themes the page.

**Light mode is the default.** Dark only opts in via stored
`localStorage.pichess.theme === 'dark'`. OS-level `prefers-color-scheme`
is intentionally ignored. There's a synchronous bootstrap script in
`<head>` that applies the `dark` class before paint to avoid FOUT; the
Scala.js bundle re-derives on mount via `Logic.decideInitialTheme`.

### Built so far

| Surface | Implementation |
|---|---|
| **Page background** | Inline `<svg><use href="#paper-crumpled-square"/></svg>` rendered fixed behind everything. The symbol is **inlined** in `HtmlPage.scala`'s `.svg-sprite-host`. |
| **Header** | Sticky, hosts a `paperLayer()` with the gridded paper. `clip-path: var(--torn-bottom)` is applied to the **inner `<svg>` element** (`.header > .paper-layer > svg`) — putting it on `.paper-layer` itself broke the drop-shadow, putting it on `.header` broke sticky. |
| **Board** | `.board-paper` wraps status indicator + `.board-row` (board-wrapper + captured-pile). Tiles are translucent (`rgba(...,0.72)`) so paper texture shows through; tiles have a subtle `--torn-tile` clip-path with the top edge pushed *out* of the polygon so tall pieces (king/queen) overflow upward unclipped. Board paper uses `--torn-corners`. |
| **Captured pile** | Right gutter, split into `.captured-section-top` + `.captured-section-bottom` by *who took whom*. Default orientation: white-pieces-taken-by-black on top, black-pieces-taken-by-white on bottom. Flipped board inverts the assignment. **Top section's DOM order is reversed in Scala** so older pieces paint on top of newer (user-requested z-flip). 7-step nth-child rotation jitter on each piece for the scattered look. |
| **Sidebar panels** | Move-log paper (`.move-log-paper`) + adhesive-tape pseudo-elements (`::before` + `::after`) at the top corners of each panel. `--tape-rgb` mutes the tape in dark mode. |
| **Move log** | Stable outer `.move-log` (overlayScrollbars init target) wraps a dynamic `.move-log-inner` (Laminar children). Padding on the inner; OS doesn't touch it. Clip-path `--torn-left-bottom` on the inner SVG so the right edge stays straight for the OS handle. |
| **Post-it buttons** | Sharp corners (`border-radius: 0`), one of three `--post-shape-{a,b,c}` clip-path polygons (varied folded-corner + bottom-curve combinations). Two-layer `filter: drop-shadow` (one neutral dark layer for elevation + one tinted for the post-it colour glow). Per-button `--pn-bg` / `--pn-shadow` / `--post-shape` overrides. Action vocabulary: `--post-yellow` primary, `--post-cyan` secondary, `--post-coral` destructive. |
| **Newspaper-cutout buttons** | `.header-link` only (Help, Docs, ← Game). `--newsprint` background + the crumpled SVG as `background-image` blended via `multiply` for wrinkle texture. `clip-path: var(--torn-corners)` + Special Elite typewriter font. |
| **Marker highlights** | `.section-title::before` uses `mask-image: url(/web/marker-stripe.svg)` + `background: var(--marker-yellow)`. The mask SVG has both shape-warp and streaky alpha modulation (two `feTurbulence` chains) for the brush-stroke variation. Marker is neon orange with low alpha so paper texture shows through. |
| **Type system** | Caveat (body), Caveat Brush (logo + banners), Special Elite (move log + newsprint + code). Loaded from Google Fonts CDN with `display=swap`. `:root { font-size: 137.5%; }` makes 1rem = 22px effective. |
| **Drag** | Native HTML5 drag with custom `setDragImage(clone, …)`. Clone positioned at `(0, 0)` with `opacity: 0` (NOT `top: -9999px` — Chrome won't capture far-offscreen elements). `onDragOver` sets `dataTransfer.dropEffect = "move"` so the move cursor appears. |
| **Theme toggle** | `.theme-toggle-btn` post-it in `.header-actions`. Glyph reflects target mode (☾ when in light = "click for dark"). `min-width: 2.4rem` keeps the button at constant width when the glyph swaps. |

---

## Open issues — please address first

### 1. `.move-log-inner` content overflows the torn paper edges (UNRESOLVED)

The torn paper silhouette is on `.move-log-paper > .paper-layer > svg`
(inner SVG only). The `.move-log` content area is rectangular and
extends to the rectangular `.move-log-paper` content box — when rows
fill the panel, parts of them visibly extend past the torn left/bottom
edges into the page beneath.

Already tried:
- Move padding from `.move-log` onto `.move-log-inner`, `width: 100%`,
  `box-sizing: border-box` — didn't fix it (so the issue isn't padding;
  it's that the rectangular content area itself sticks out past the
  torn polygon).

Candidate fixes (pick one to try next):

- **A.** Apply `clip-path: var(--torn-left-bottom)` to `.move-log-paper`
  itself (not just its inner SVG). Visible content gets clipped to the
  torn silhouette. Tradeoff: the `filter: drop-shadow` on `.paper-layer`
  was specifically arranged so the shadow follows the torn edge while
  not being clipped by an ancestor — clip-path on the paper container
  may cut the shadow off again. Worth testing whether the shadow still
  reads.
- **B.** Apply `clip-path` to `.move-log` (the OS target). Content
  inside the OS viewport gets clipped to the torn shape; paper SVG
  drop-shadow stays unclipped. Risk: OS might struggle with a clip-path
  on its target since it sets up its own scroll wrapper.
- **C.** Bump `.move-log-paper` padding so the content area sits
  comfortably inside even the deepest jag of the torn polygon (~0.6rem
  in). Trades visual real estate for safety. Easiest, least clever.

I'd start with C (cheap, no architectural risk), and if that visually
loses too much usable area, fall back to A and verify the shadow
still reads.

### 2. OS handle styling — currently debug-plain red

`.os-theme-pichess .os-scrollbar-handle { background: red; }` so we can
verify the OS integration is actually working before doing the
hand-drawn styling. **The user explicitly asked NOT to proceed past this
step until they OK it.**

When green-lit, step 2 of the OS work is to:
- Apply `filter: url(#hand-drawn)` to the handle (the filter is already
  inlined in `HtmlPage.scala`'s sprite host).
- Replace the red background with a hatched pattern. Two options:
  - Inline data-URL SVG (simplest — but see the dark-mode caveat
    below).
  - **Extract the hatch into a separate file** (e.g.
    `web/hatch.svg`) so it can use `currentColor` for stroke and
    inherit theme colours via CSS — user suggested this and I think
    it's the right call.
- Wire OS arrow buttons using
  `web/doodle_icons/arrows/chevrons-down.svg` and `chevrons-up.svg`.
  OS supports custom arrow elements; check 2.x docs for the option
  name (likely `scrollbars.clickScroll` or via custom theme classes).

### 3. Hatch / data-URL CSS-var limitation (heads-up for step 2)

CSS variables **don't propagate into `data:` URLs**. The user's hatch
snippet uses `stroke='black'` (light-mode dark) hard-coded. In dark
mode this would be barely visible against the dark paper.

Solutions when we get to step 2:
- Extract hatch into a same-document SVG sprite using
  `stroke="currentColor"`, then set `color: var(--text-primary)` on
  the handle in CSS. CSS `color` cascades reliably; SVG `currentColor`
  picks it up.
- OR write two separate `data:` URLs (one per theme) and switch via
  `:root.dark` rule.
- OR build the hatch as two stacked `linear-gradient`s that use
  `var(--text-primary)` directly (no SVG involved).

The first option is the cleanest and matches the architectural
direction we're already taking with inline SVG sprites.

### 4. Other CSS-var cascade caveats encountered

- **`baseFrequency` + `surfaceScale` on SVG filter primitives** are NOT
  standardised CSS properties. Attempts to expose
  `--crumple-frequency` / `--crumple-depth` via inline `style="…"` on
  `feTurbulence` / `feDiffuseLighting` are best-effort — most
  browsers ignore them. Documented in both square SVGs' header
  comments. If per-panel crumple density coherence becomes important,
  the proper fallback is a separate SVG variant per density tier.

- **Cross-document `<use href="external.svg#id">`** has flaky CSS-var
  cascade for vars buried inside `<pattern>` and `<feDiffuseLighting>`
  — was the cause of the "grid colour doesn't change in dark mode"
  bug. **Fixed** by inlining both paper symbols in
  `HtmlPage.scala`'s `.svg-sprite-host` and using document-internal
  `<use href="#id">`. If you add more SVG sprites, add them to the
  same host for the same reason.

### 5. SVG comment double-dash rule

`--` is illegal anywhere inside an XML comment. When documenting CSS
custom properties in SVG header comments (e.g. listing tunable hooks),
write the variable name **without** the `--` prefix:
`paper-color`, not `--paper-color`. The `--` prefix is only valid in
actual CSS code. Memory entry exists at
`memory/feedback_svg_comment_dashes.md`.

---

## Next design steps (in priority order)

After the open issues above are resolved.

### Layout pass: three grouped sidebar post-its (decided)

Replace the current row-of-individual-post-its sidebar with three
larger post-it cards. Buttons inside each post-it are styled as
**plain text** by default; the marker stripe shows **on hover only**.
Headings inside post-its get a hand-drawn underline (CSS clip-path or
SVG mask, similar pattern to `.section-title`'s marker but
underline-shaped, not strip-shaped). The post-it card itself does NOT
get a heading — the buttons are the content.

| Post-it | Colour | Contents |
|---|---|---|
| 1 | yellow (default) | New Game + Move folded together |
| 2 | yellow (default) | Undo / Redo / Flip → followed by an "**Export**" sub-heading with hand-drawn underline → followed by FEN / PGN / JSON |
| 3 | coral | Draw / Forfeit / Quit / Load |

User confirmed the post-it has NO own heading; only the inner "Export"
section gets one.

### Icon vs text strategy (proposed, not fully confirmed)

I suggested:
- **Icon-only** for buttons whose meaning is universal: Undo, Redo,
  Flip (the doodle-icons arrows are already perfect for this).
- **Text-only** for the data formats: FEN, PGN, JSON (no icon mapping
  fits).
- **Icon + text** for the destructive pile: Draw, Forfeit, Quit, Load
  (the icon plus the word reduces accidental clicks).

User hasn't confirmed yet. They mentioned text-only is also acceptable.
Confirm this before implementing the layout.

### Doodle icons available

Path: `gateway/src/main/resources/web/doodle_icons/`. Categories:
arrows, currency, e-commerce, emojis, files, finance, food,
gender symbols, hand gestures, health, interface, logos, misc,
objects, weather. Plenty to draw from for the above + future passes.

User specifically called out
`web/doodle_icons/arrows/chevrons-down.svg` for the OS scrollbar
arrows in step 2.

---

## File map

```
gateway/src/main/scala/chess/view/
  HtmlPage.scala                  ← inlines style.css + paper sprites + hand-drawn filter
                                    + OS CDN links + theme bootstrap script

gateway/src/main/resources/web/
  style.css                       ← all styling
  notebook-page-crumpled-square.svg       ← gridless paper, page bg
  notebook-page-crumpled-grid-square.svg  ← gridded paper, panels + header
  marker-stripe.svg               ← mask SVG for .section-title highlight
  peach.svg                       ← logo (untouched, sprite pattern)
  pieces/*.svg                    ← chess piece sprites (untouched)
  doodle_icons/                   ← icon set for next pass
  notebook-page.svg, notebook-page-crumpled.svg
                                  ← legacy portrait variants, no longer
                                    referenced from Laminar; kept on disk

web-ui/src/main/scala/chess/webui/
  Main.scala                      ← Laminar UI; paperLayer, capturedPile,
                                    moveLogContainer, theme toggle, drag
  Logic.scala                     ← pure helpers; Theme enum + decideInitialTheme

gateway/src/test/scala/chess/view/
  HtmlPageSpec.scala              ← asserts inline assets are embedded

web-ui/src/test/scala/chess/webui/
  LogicSpec.scala                 ← incl. decideInitialTheme cases
```

---

## Build / test

Always before declaring done (per `.ai/BRIEFING.md`):

```
sbt --no-server scalafmtAll
sbt --no-server "; gateway/clean; coverage; gateway/test; gateway/coverageReport; webUi/compile; webUi/test"
```

Gateway coverage must be 100% statement + branch (build fails below).
The single Windows-only failing test (`chess.MainSpec - runCommand`
trying to spawn the Unix `true` builtin) is a pre-existing
platform-incompatibility, ignore it on Windows.

---

## Known environment quirks (Windows specifically)

- **`sbt` boot lock**: `Could not create lock for \\.\pipe\sbt-load…` —
  a stale named pipe from a previous sbt run gets stuck. `--no-server`
  helps once the kernel releases the pipe; if it's still stuck, wait
  ~30 seconds and retry. No clean kill since there are usually no live
  java processes holding it.

- **Windows Firewall prompts on every `sbt run`**: zio-http binds to
  `0.0.0.0` by default, which Windows treats as a network-facing
  service. Pending TODO (user said "later") — make the bind address
  env-var-driven (default `127.0.0.1` for dev, Docker compose sets
  `0.0.0.0`). Affects `app/Main.scala:95` and
  `repository/RepositoryMain.scala:35`.

---

## Memory entries

`memory/MEMORY.md` indexes:

- `ref_ai_briefing.md` — `.ai/BRIEFING.md` is the project's AI
  instructions hub; read it first.
- `feedback_svg_comment_dashes.md` — the `--` rule above.

Both should already be loaded automatically into a fresh session via
the agent's auto-memory system.
