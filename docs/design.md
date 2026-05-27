# piChess UI design system

> Forward-looking spec. The current code only partially matches it — the
> "Refactor checklist" section at the end calls out exactly what to bring
> into line. When in doubt, this doc wins; the CSS / Scala can be edited
> to fit.

## 1. Why this document exists

Until now each screen has been styled in isolation. Adjacent screens
ended up with different button shapes, different padding rhythms,
different ways of handling tilt, different ways of building forms.
Each one looked OK on its own; together they read as inconsistent.

The fix is upstream: define a small set of layout primitives,
components, and tokens, and require every new screen to compose from
that set. The doc below is that set.

The aesthetic decisions stay simple — _pixar-coded notebook page,
casually pinned together, hand-drawn vibe_ — but the surface area for
new bespoke pieces shrinks to near zero. If you find yourself
inventing a new button shape on a new screen, the answer is almost
always "use the existing primitive" or "extend the spec here first,
then implement everywhere".

## 2. Aesthetic principles

1. **The page is a notebook**, not an app. Every surface is a paper
   scrap; every interactive element is the kind of mark you'd make on
   paper (handwritten label, marker highlight, sticky note, taped
   clipping).
2. **Static "casually pinned" tilts**, not random ones. Two scraps
   adjacent to each other counter-lean. Tilts are small (≤ 2°) for
   primary surfaces, larger (≤ 8°) for stickers / decorations.
3. **Type carries the brand**. Caveat / Caveat Brush is the
   handwriting layer; Special Elite is the typewriter layer for
   "printed" data (move logs, FEN, code, raw IDs). Headings and
   buttons live in the handwriting layer; system data lives in the
   typewriter layer.
4. **Colour is reserved for hierarchy.** Most ink is the warm dark
   `--post-text` brown. Yellow = primary action. Cyan = secondary.
   Coral = destructive. Newsprint = navigation link. Anything else is
   a one-off and should justify itself.
5. **Decorations are ambient, not load-bearing.** Doodles, scribbles,
   marginalia, and the chess-piece shelf at the bottom are texture —
   never required to understand or operate the screen.

## 3. Design tokens

All tokens are CSS custom properties on `:root` (or `:root.dark` for
the dark-mode override). Reach for these by name; never hard-code a
hex / rem / px value that has a token.

### 3.1 Type

| Token | Value | When |
|---|---|---|
| `--font-logo` | `'Caveat Brush', cursive` | Wordmark + screen headings |
| `--font-hand` | `'Caveat', cursive` | Body, buttons, form labels, menu items |
| `--font-press` | `'Special Elite', 'Cascadia Code', monospace` | Move log, FEN, code, raw IDs |

Root font-size is fluid: `clamp(1em, 0.5em + 0.6vw, 1.375em)`.
**Always size in `rem`** so accessibility settings and the fluid root
flow through.

#### Type scale (rem)

| Use | Size | Family |
|---|---|---|
| Wordmark (start screen brand) | 5.5 rem | logo |
| Screen heading (`.screen-heading`) | 2.75 rem | logo |
| Sub-heading | 1.5 rem | logo or hand |
| Menu item | 2 rem | hand |
| Button / tab / form label | 1.5 rem | hand |
| Body / blurb | 1.125 rem | hand |
| System data (move log, code) | 1 rem | press |

### 3.2 Colour

#### Paper + ink (re-themed by `:root.dark`)

| Token | Light | Dark | Role |
|---|---|---|---|
| `--paper-color` | `#ffe5d5` | `#3a2a22` | Page background |
| `--paper-grid` | `#fff1e2` | `#4a382e` | Card paper (always brighter than `--paper-color` so grid reads) |
| `--grid-color` | `#8aafc8` | `#806856` | Grid lines on card paper |
| `--text-primary` | `#2d1b14` | `#f5e6dc` | Body text |
| `--text-secondary` | `#6b4d3c` | `#d8c4b6` | De-emphasised text |
| `--text-muted` | `#8a6a5a` | `#b09a88` | Captions, helper text |
| `--header-bg` | `#34201a` | `#1a100c` | Header strip — kept dark in both themes |
| `--header-text` | `#f7a072` | `#f7a072` | Wordmark colour |

#### Action palette (post-it stickers)

| Token | Hex | Role |
|---|---|---|
| `--post-yellow` / `--post-yellow-shadow` | `#fff2a3` | **Primary** — the only "do this" colour |
| `--post-cyan` / `--post-cyan-shadow` | `#b8e8eb` | **Secondary** |
| `--post-coral` / `--post-coral-shadow` | `#ff9a8a` | **Destructive** — reserved for forfeit / delete / quit |
| `--newsprint` / `--newsprint-shadow` | `#f8eab0` | **Navigation** — Help / Docs / Back, anything that opens a new screen |
| `--post-text` | `#2d1b14` | Ink colour for any sticker / clipping |

#### Marker highlight

| Token | Use |
|---|---|
| `--marker-yellow` | Default highlight under hovered text (and `is-active` tabs) |
| `--marker-pink` / `-blue` / `-green` / `-red` | Reserved per-action variants — pick one only when "yellow" would not communicate intent |

### 3.3 Shape — torn / clipped polygons

| Token | When | Edges |
|---|---|---|
| `--torn-bottom` | Headline / title cards | Top + sides clean, bottom ragged |
| `--torn-left-bottom` | Cards adjacent to a clean right edge (sidebar) | Top + right clean, left + bottom torn |
| `--torn-right-bottom` | Mirror of above | Top + left clean, right + bottom torn |
| `--torn-corners` | Boards / large panels | All four corners torn, edges clean |
| `--torn-all` | Free-floating scraps | All edges torn |
| `--torn-tile` | Individual board squares | Subtle wobble, top edge pushed up so tall pieces escape |
| `--post-shape-a` | Default sticky note | Top-right corner cut, bottom bowed |
| `--post-shape-b` | Sticky note pasted right of something | Top-left corner cut |
| `--post-shape-c` | Icon-only sticky | No corner cut |

### 3.4 Spacing

| Token | rem | Use |
|---|---|---|
| `--space-1` | 0.25 | Tight inline gap |
| `--space-2` | 0.5 | Form-row gap |
| `--space-3` | 0.75 | Card padding (snug), inter-element gap |
| `--space-4` | 1 | Card padding (default), section gap |
| `--space-5` | 1.5 | Card padding (loose), section gap on title cards |
| `--space-6` | 2 | Outer screen padding |

> ⚠️ **Not yet defined as variables.** Add a `--space-{1..6}` block in
> `:root` and migrate hard-coded `padding`/`gap`/`margin` values.

### 3.5 Shadow

| Token | Value |
|---|---|
| `--panel-shadow` | `0 0.9rem 1rem rgba(0,0,0,0.10)` (light) / `0 1rem 1.4rem rgba(0,0,0,0.35)` (dark) |
| `--post-shadow-base` | `drop-shadow(0 0.18rem 0.2rem rgba(0,0,0,0.18))` — sharp dark line under any post-it's adhesive edge |
| `--post-yellow-shadow` etc. | Per-sticker colour halo — paired with `var(--post-*)` for the soft outer glow |

Always use **filter: drop-shadow(...)** on the wrapper of any clipped
element — `clip-path` clips `box-shadow` away. (Already a comment in
the CSS; restated here as a rule.)

**Post-its stack two layers**: a sharp dark contact shadow
(`--post-shadow-base`) plus a softer colour-tinted halo. Composed at
the call site:

```css
.my-post-it {
  filter: var(--post-shadow-base) drop-shadow(0 0.5rem 0.7rem var(--post-yellow-shadow));
}
```

The base layer reads as "the paper is actually on top of the page";
the colour layer ties the shadow tint to the sticker's own pastel.
Single-layer post-its look flat — both layers always.

### 3.6 Tilt scale

| Element | Range |
|---|---|
| Title card | -1.4° to -1.6° |
| Content card (counter-tilt to title) | +1.0° to +1.2° |
| Side post-it | +5° to +7° (always leans the same direction as its parent card) |
| CTA button | -1° to +1° |
| Decoration / shelf piece | -8° to +8° (only pseudo-static — see §6) |

**Adjacent surfaces always counter-tilt.** Title leans left → menu
card leans right; menu card leans right → side post-it leans right
(it doubles down on the parent's lean to read as a casual addition).

## 4. Layout primitives

These are the building blocks every screen composes. They live in
CSS as classes; in Scala they're rendered through helper functions.

### 4.1 `.page-bg` — page background

The crumpled paper texture, mounted **once** at the App root (next to
`toastElement()`), `position: fixed; z-index: -1`. Every screen
inherits it; never re-mount it inside a screen.

### 4.2 `paperLayer()` + `.paper-wrap` — paper card

A "scrap of paper" surface. The wrapper (`.paper-wrap`) is
`position: relative` so the paper SVG inside it (`<div class="paper-layer">`)
absolutely fills the box, with content rendered as additional children
that sit above (z-index handled by a global `:not(.paper-layer)` rule).

Every paper card needs:
- `--paper-color: var(--paper-grid)` override (gridded surface)
- `--grid-minor-size` / `--grid-major-size` so the grid renders at the
  intended visible scale
- `filter: drop-shadow(var(--panel-shadow))` to recover the shadow
  that `clip-path` would otherwise eat
- A `clip-path: var(--torn-...)` on `> .paper-layer > svg`
- A static `transform: rotate(...)` per §3.6
- `position: relative` so absolute children (e.g. corner tape, side
  post-it) anchor to it

### 4.3 Tape strips

Two corner pseudo-elements (`::before` + `::after`) on any card that
should look "pinned". The shared rule lives near the top of `style.css`
— append a card class to that selector list rather than duplicating
the rule.

When tape needs to bridge a gap (e.g. menu card → title card above),
override `top` and `height` on the card's `::before/::after` so the
strip visibly spans both surfaces.

### 4.4 Post-it sticker

The yellow / cyan / coral / newsprint variants of the action palette.
Built from:
- `clip-path: var(--post-shape-{a,b,c})`
- `background: var(--post-{colour})`
- `filter: drop-shadow(0 ... var(--post-{colour}-shadow))`
- A small static rotation
- `color: var(--post-text)` for the ink

A post-it is **always** a CTA, a navigation chip, or a sticker
decoration — never just a label.

### 4.5 Marker highlight

A linear-gradient background image painted at `background-size: 0% 60%`
and animated to `100% 60%` on `:hover` / `:focus-visible`. Used for
menu items, tabs, and inline links. The gradient angle is `105deg` and
the colour comes from `--marker-yellow` (or a per-action variant).

```css
background-image: linear-gradient(
  105deg,
  rgba(255, 220, 90, 0) 0%,
  rgba(255, 220, 90, 0.78) 8%,
  rgba(255, 220, 90, 0.85) 92%,
  rgba(255, 220, 90, 0) 100%
);
background-size: 0% 60%;
transition: background-size 0.18s ease-in-out;
```

> ⚠️ The exact gradient should become a `--marker-stripe-bg` variable
> so per-screen overrides (different colour, different height) don't
> have to copy the whole declaration.

### 4.6 Piece shelf

The chess-piece row at the bottom of the start screen. Reusable on
any "landing" screen via `pieceShelf()` — it's purely decorative and
always has `pointer-events: none`. Don't put it on screens with
content near the bottom edge (lobby room, settings) since it crowds
the active controls.

## 5. Component system

Every interactive element on every screen MUST be one of the
following. If a need genuinely doesn't fit, extend this section
*first*, implement *second*.

### 5.1 Buttons

Three classes, no others.

| Class | When | Visual |
|---|---|---|
| `.btn-cta` | The primary action of a screen / panel. One per local context. | Yellow post-it, post-shape-a, drop-shadow, ~1° tilt, marker-stripe hover (deeper colour), tap-down translateY |
| `.btn-secondary` | Secondary action (Cancel, Skip, Refresh). | Cyan post-it, post-shape-b or -c, otherwise identical to CTA |
| `.btn-destructive` | Forfeit, Delete, Quit | Coral post-it, post-shape-a, otherwise identical to CTA |

**Plus** two non-post-it variants:
| Class | When | Visual |
|---|---|---|
| `.btn-link` | In-flow text-style action (menu item, settings row link, mode tab, doc link) | Plain transparent button, hand font, marker-stripe hover |
| `.btn-icon` | Header back-arrow, theme toggle, single-glyph control | Plain transparent button, slight opacity, scale on hover |

> ⚠️ Today's CSS has bespoke selectors per call site
> (`.start-menu-item`, `.mode-tab`, `.start-side-link`, `.mode-cta`,
> `.btn`, `.theme-toggle-btn`, etc.). Refactor: collapse to the five
> classes above + per-screen layout positioning rules only.

### 5.2 Form controls

| Class | Element |
|---|---|
| `.form-row` | Wrapper for one label + one control (always handwritten label, never floating placeholder) |
| `.form-row-checkbox` | Variant for checkbox rows (the label sits to the right of the box) |
| `.form-row-label` | The handwritten label text |
| `.form-control` | Any `<input>` / `<select>` / `<textarea>` — same handwritten font, faintly tinted background, single-pixel hairline border |

> ⚠️ Numeric inputs and selects should use the same `.form-control`
> styling — currently number inputs in different screens look
> different. Once `.form-control` is universal, drop bespoke selectors.

### 5.3 Tab strip

`.tab-strip` is a flex row of `.btn-link` children separated by a
dotted hairline below (`border-bottom: 1px dashed var(--hairline)`).
The active tab pins its marker stripe on (`is-active`); a disabled
tab is `opacity: 0.5` with `cursor: not-allowed` and no marker stripe.

### 5.4 Screen heading

A single `.screen-heading` element (`<h1>`, logo font, 2.75 rem). Sits
inside the title card. **Never** more than one per screen.

### 5.5 Back link

`.back-link` — a `.btn-icon` rendering "← Back" (or "←" alone). Goes
either inside the title card on the left, or as the very first child
of the screen (top-left). Behaviour is `dom.window.history.back()`
with a `Screen.Start` fallback when the history stack is empty.

### 5.6 Toast

Already a single global element rendered at the App root. Don't
re-render per screen. Use `showToast(...)` from any handler.

### 5.7 Modal / dialog

`.modal-dialog` paper-card variant — same primitives as a regular
card (paperLayer + tape + drop-shadow), centred in a `.modal-overlay`
that holds the `--overlay-bg` scrim. Existing rules in `style.css`.

**Scroll lock is mandatory.** Whenever any modal is open, the page
beneath must not scroll. The web-ui solves this with one shared rule
(`body.modal-open { overflow: hidden; }`) toggled from a derived
signal that combines every modal's open-state Var:

```scala
Signal
  .combine(
    pendingPromotionVar.signal.map(_.isDefined),
    loadOpenVar.signal,
    confirmVar.signal.map(_.isDefined),
    exportVar.signal.map(_.isDefined)
  )
  .map { case (a, b, c, d) => a || b || c || d }
  .distinct
  .foreach { open =>
    val cl = dom.document.body.classList
    if open then cl.add("modal-open") else cl.remove("modal-open")
  }
```

When adding a new modal, add its open-state Var to that combine list
(or — better, see §13.4 — register it through a single
`registerModal(signal)` helper).

## 6. Screen skeleton

Every **configuration-style** routed screen (start, new game, join,
lobby, settings, help, docs) MUST follow this skeleton:

```
.screen.screen-<name>           ← position: relative; full-viewport flex column; centered
  ├ paperLayer (only if the screen needs its own bg variant — usually not)
  ├ <title card>                 ← .paper-wrap with .screen-heading + optional .back-link
  ├ <content card(s)>            ← .paper-wrap with the screen's controls
  ├ optional side post-it        ← absolutely positioned to a content card's right edge
  └ optional decoration          ← e.g. pieceShelf, only on landing-style screens
```

The **game screen** (`#game/<id>`) is a workspace, not a configuration
screen — the board IS the content, there's no meaningful "heading".
It uses its own pattern:

```
.app-shell                      ← header strip + game body (no centered cards)
  ├ <header>                     ← brand on the left, theme + nav links on the right
  ├ <game body>                  ← board paper + sidebar (move log + controls)
  └ <modals>                     ← rendered conditionally; covered by §5.7
```

Buttons / form controls / modals INSIDE the game shell still use the
§5 component classes — only the outermost layout deviates.

### 6.1 Container

```css
.screen-<name> {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: var(--space-6) var(--space-4);
  gap: var(--space-3);
  overflow: hidden;
}
```

### 6.2 Title card

- `.paper-wrap` + `paperLayer(crumpled = true)`
- `clip-path: var(--torn-bottom)` on the inner SVG
- `transform: rotate(-1.4deg)`
- Padding: `1.5rem 3rem 1.25rem`
- Holds at most: a back link (left), the `.screen-heading` (centre)

### 6.3 Content card

- `.paper-wrap` + `paperLayer(crumpled = true)`
- `clip-path: var(--torn-left-bottom)` on the inner SVG
  (preserves the right edge for any side post-it to attach to)
- `transform: rotate(1.1deg)` (counter-tilt)
- Padding: `1.5rem 2rem 1.75rem`
- `min-width: 22rem` so the card has enough horizontal room to
  predictably hold a tab strip + the longest form row
- `display: flex; flex-direction: column; gap: 1.25rem` for vertical
  rhythm between sections

### 6.4 Side post-it (optional, per screen)

- `position: absolute; left: calc(100% + 3rem); top: 50%;`
- `transform: translateY(-50%) rotate(6deg)`
- Width: 8 rem
- Always hosts links / chips, never a destructive action

### 6.5 Decoration slot (optional)

`pieceShelf()` along the bottom **only** on landing-style screens
where the bottom area is otherwise empty (Start). Don't crowd
controls.

## 7. Tilt + rotation conventions

Recap of §3.6 with the rules:

1. **Two adjacent cards always counter-tilt.** Pick one as "leans
   left", the other "leans right". Magnitude ≤ 2° for primary
   surfaces.
2. **Tape rotation matches the card it sits on.** The shared tape
   rule rotates `::before` by -4° and `::after` by +4°; don't override
   per card unless the card itself is rotated by a magnitude that
   visually conflicts.
3. **A sticker on a card leans the same direction as the card.**
   Doubling the lean reads as "casual addition". The side post-it
   rule (`+6°` on a `+1.1°` card) is the canonical example.
4. **Decorations may use pseudo-static random tilt** (e.g. piece
   shelf), seeded once at render. Never live-random per signal.
5. **Don't animate rotation.** Rotation is identity. Animate
   `translateY` for hover lift, `background-size` for marker stripe,
   `opacity` for fade — never the angle.

## 8. Theme rules

- One class flip on `<html>` (`.dark`) re-themes everything via the
  `:root.dark` token overrides — no per-component dark-mode rule should
  exist. **If you find yourself writing `:root.dark .my-thing`, fix it
  by reading from a token instead.**
- Decorations (doodles, scribbles, ambient marks) inherit `color` from
  the parent and use `currentColor` in their fill / mask. The parent's
  `color` token (e.g. `--text-secondary`) drives both modes.
- The header strip's background (`--header-bg`) is intentionally dark
  in both modes — the leather binding stays leather.

## 9. Iconography

### 9.1 Chess pieces

Inline-SVG sprites injected once into the page via `HtmlPage.scala`'s
sprite host. Always rendered through `pieceSvg(name)`; the parent's
`color` (white-piece / black-piece classes) drives the `--piece-primary`
/ `--piece-secondary` cascade.

### 9.2 Doodles

Two packs:
- `/web/doodle_icons/` — older, hard-fill black SVGs. **Don't add new
  uses.** Existing call sites should migrate.
- `/web/dddoodle_pack/` — newer, sketchy `currentColor` SVGs with
  built-in wobble distortion. **Use this pack for any new
  decoration.** Apply via `mask-image` + `background-color: currentColor`
  so the colour cascades through and dark-mode handling is automatic.

> ⚠️ **Decorative doodle layer is currently empty** — the previous
> marginalia / arrow-cluster / question-cluster experiments were
> dropped. The doodle-pack section in this doc is a forward-looking
> primer for when we re-introduce decorations on a per-screen basis.

### 9.3 Peach mark

The brand peach SVG is already inlined in the sprite host; reference
it as `<use href="/web/peach.svg#peach"/>`. Keep its viewBox at
`-3 -3 43 44` so the right + bottom edges of the leaf aren't clipped.

## 10. Anti-patterns

The following are explicit "no":

1. **Ad-hoc `position: absolute; top: <px>; left: <px>;`** for layout.
   Use structural anchors: `align-self`, flex flow, `calc(100% + x)`,
   `inset: 0`. Absolute positioning is fine when it's relative to a
   structural anchor; not when it's relative to a viewport coordinate
   the layout will silently break under.
2. **Per-element dark-mode rules** (`:root.dark .my-thing`). Read a
   token. If no token fits, define one.
3. **Per-element filter chains** for icon recolouring. Use `mask-image`
   + `currentColor`. The whole `filter: brightness(0) saturate(100%)
   invert(...) sepia(...) hue-rotate(...)` chain is a code smell.
4. **Random rotations recomputed per render.** Pseudo-random fine
   (seed once at first render). Live random no.
5. **Bespoke button shapes per screen.** If `.btn-cta` doesn't fit,
   the answer is to extend the spec, not invent `.start-cta`,
   `.mode-cta`, `.lobby-cta`, etc.
6. **px values for anything user-facing.** `rem` for sizing,
   `0.something rem` for hairlines below 1px equivalents. Px is
   reserved for `box-shadow` blur (where rem rounds badly).
7. **Heading / wordmark variants per screen.** One `.screen-heading`,
   one `.start-brand`. Don't fork them.
8. **Mounting `pageBackground()` inside a screen.** It's a global; it
   lives at the App root.

## 11. Refactor checklist

Concrete drift the doc above prescribes a fix for. Execute these in
the order listed; each one is independently shippable.

### 11.0 Foundation: Tailwind 4 + Scala component helpers ✅ DONE

Landed across tasks #46–#52. Tailwind v4.3 standalone CLI is wired
into `make tailwind-{install,build,watch}`; `gateway/src/main/tailwind/
input.css` declares `@theme` tokens + `@custom-variant dark` + imports
the bespoke CSS layer; helper catalogue lives at
`web-ui/src/main/scala/chess/webui/components/`. Every routed screen
(start, new-game, join, lobby, settings, help, docs) renders through
the helpers. The game screen keeps its own `app-shell` workspace
pattern (board IS the content) but its modals + buttons go through
the same helpers. ~360 lines of legacy bespoke CSS deleted in #52.

### 11.1 Spacing tokens
Add `--space-{1..6}` to `:root`. Migrate hard-coded `padding` / `gap` /
`margin` values in the start, new-game, lobby, settings, help, docs
screen rules.

### 11.2 Marker-stripe variable
Extract the `linear-gradient(105deg, ...)` declaration into
`--marker-stripe-bg`. Use it in `.start-menu-item`, `.start-side-link`,
`.mode-tab`, and any future `.btn-link`.

### 11.3 Button system
Collapse all current button classes to the five canonical classes
(`.btn-cta` / `.btn-secondary` / `.btn-destructive` / `.btn-link` /
`.btn-icon`). Bespoke per-screen selectors should only carry layout
(position, size, margin), not appearance.

### 11.4 Form controls
Define `.form-row`, `.form-row-checkbox`, `.form-row-label`,
`.form-control`. Migrate the host-game form, the settings nickname
input, and the join-by-code input.

### 11.5 Screen skeleton
Every screen function in `Main.scala` should render the skeleton in
§6 — title card + content card stack. Currently `helpScreen`,
`docsScreen`, `settingsScreen`, `joinScreen`, `lobbyScreen` use plain
`<div>` + `<h1>` with no paper card. Bring them into the skeleton.

### 11.6 Drop the legacy doodle pack
Once nothing uses `/web/doodle_icons/`, the directory can go. Today
nothing references it — consider dropped.

### 11.7 Heading consistency
`.screen-heading` is the only screen heading. Currently:
- Start: uses `.start-brand` (different — keep, it's the brand wordmark)
- New game: `.screen-heading` (canonical)
- Help / Docs / Settings / Join / Lobby: bare `<h1>` (fix)

### 11.8 Tilt audit
Any element with a `transform: rotate(...)` should match §3.6 magnitudes.
Spot-check the lobby + settings cards once they get their title cards.

### 11.9 Theme overrides audit
Grep for `:root.dark`. Each one should either be in the canonical
token block (`:root.dark { --paper-color: ...; ... }`) or be deleted
and replaced by reading from a token.

## 12. Implementation: Tailwind 4 + Scala component helpers

The bespoke-class drift is also an implementation problem: every time
we want a button on a new screen, the path of least resistance is
"write yet another bespoke selector". The structural fix is twofold:

1. **Tailwind 4 utilities** for layout, spacing, sizing, colour, and
   typography. The `@theme` block in `style.css` declares the design
   tokens from §3 as Tailwind theme entries — one source of truth,
   accessible both as CSS custom properties and as utility classes
   (`bg-paper-grid`, `text-text-primary`, `font-hand`, `gap-3`, etc.).
2. **Scala component helpers** (Laminar functions) for every reusable
   pattern. Each helper carries its own utility-class kit, encapsulating
   the "look" so the call site only deals with the "what":

   ```scala
   def card(...content: Modifier[HtmlElement]*): HtmlElement =
     div(
       className := "paper-wrap relative ... ",   // utility-class kit
       paperLayer(crumpled = true),
       content
     )

   def ctaButton(label: String)(action: dom.MouseEvent => Unit): HtmlElement =
     button(
       typ := "button",
       className := "btn-cta inline-flex ...",     // utility-class kit
       label,
       onClick --> action
     )
   ```

### 12.1 Division of labour

| Layer | Belongs to |
|---|---|
| Layout (flex, grid, gap, padding, margin) | Tailwind utilities |
| Sizing (width, height, min/max) | Tailwind utilities |
| Colour (bg, text, border) | Tailwind utilities, mapped to design tokens |
| Typography (font family, size, weight, line-height) | Tailwind utilities |
| Hover / focus / dark mode variants | Tailwind variants |
| Paper SVG layer + sprite cascade | Bespoke CSS |
| Tape strip pseudo-elements | Bespoke CSS |
| Post-it clip-paths + two-layer drop-shadow | Bespoke CSS |
| Marker-stripe gradient | Bespoke CSS (or `@theme` background entry) |
| Doodle `mask-image` rules | Bespoke CSS |
| Screen container patterns | Scala helpers + Tailwind utilities |
| Buttons / form controls / tab strips | Scala helpers + Tailwind utilities |

### 12.2 The `@apply` rule

> **Don't use `@apply`.** It re-creates the bespoke-selector problem.

The whole point of utilities is that the styling decision lives at the
call site (or inside a Scala helper); inlining utilities into a CSS
selector via `@apply` defeats both Tailwind's atomic value prop and
the design system's "one source of truth at the call site" intent.

Two narrow exceptions:
- **Migration bridge** — temporarily wrap a legacy selector with
  `@apply` while migrating its call sites to the new helper. Delete
  the bridge once the call sites are migrated.
- **Pure decoration** — bespoke things like the tape pseudo-elements
  or the post-it clip-paths that aren't expressible as utilities at
  all stay as plain CSS.

### 12.3 Catalogue of Scala component helpers

Every helper below corresponds to a §5 component class. Spec'd here
for once the migration starts.

```scala
// Layout primitives
def screenLayout(name: String, body: Modifier[HtmlElement]*): HtmlElement
def card(variant: CardVariant, body: Modifier[HtmlElement]*): HtmlElement
def titleCard(heading: String, back: Boolean = false): HtmlElement
def contentCard(body: Modifier[HtmlElement]*): HtmlElement
def sidePostIt(body: Modifier[HtmlElement]*): HtmlElement

// Buttons
def ctaButton(label: String)(action: dom.MouseEvent => Unit): HtmlElement
def secondaryButton(label: String)(action: dom.MouseEvent => Unit): HtmlElement
def destructiveButton(label: String)(action: dom.MouseEvent => Unit): HtmlElement
def linkButton(label: String)(action: dom.MouseEvent => Unit): HtmlElement
def iconButton(label: String)(action: dom.MouseEvent => Unit): HtmlElement
def backLink(): HtmlElement

// Form controls
def formRow(labelText: String)(control: HtmlElement): HtmlElement
def checkboxRow(state: Var[Boolean], labelText: String): HtmlElement
def textInput(state: Var[String], placeholder: String = ""): HtmlElement
def numberInput(state: Var[Int], min: Int = 0, max: Int = Int.MaxValue): HtmlElement
def selectInput[A](state: Var[A], options: Seq[(A, String)]): HtmlElement

// Composite patterns
def tabStrip[A](state: Var[A], tabs: Seq[(A, String, Boolean)]): HtmlElement
def screenHeading(text: String): HtmlElement
```

Each helper assembles a Tailwind utility kit + the per-component
bespoke class (e.g. `.btn-cta` for clip-path + drop-shadow + tilt).

### 12.4 Modal registry

The Modal scroll lock signal in §5.7 hard-codes the four current
modal Vars. Moving forward, register modals through one helper:

```scala
private val modalRegistry: Var[Set[String]] = Var(Set.empty)

def registerModal(name: String, openSignal: Signal[Boolean])(using owner: Owner): Unit =
  openSignal.distinct.foreach { open =>
    modalRegistry.update(s => if open then s + name else s - name)
  }

// Bind once at App() mount:
modalRegistry.signal.map(_.nonEmpty).distinct.foreach(open => ...)
```

Any new modal calls `registerModal("loadGame", loadOpenVar.signal)`
once at App() mount and the scroll lock follows automatically.

### 12.5 Migration order

When the Tailwind migration starts, do it bottom-up:

1. **Set up Tailwind 4** — add the tooling, declare `@theme` with the
   §3 tokens, wire an sbt task that watches Scala sources and emits
   the generated CSS into `gateway/src/main/resources/web/`.
2. **Migrate one screen end-to-end** (start screen) as the proof of
   concept — extract `card`, `ctaButton`, `linkButton`, `sidePostIt`
   helpers in the process.
3. **Roll the helpers across the other screens** one by one (new
   game → join → lobby → settings → help → docs → game).
4. **Audit and delete** any per-screen CSS rule whose appearance is
   now driven by a helper. The bespoke CSS file should shrink to just
   the items in §12.1's "Bespoke CSS" column.

## 13. How to add a new screen (one-paragraph recipe)

> Render `.screen.screen-<name>`. Inside, render a title card
> (`paperLayer` + `.screen-heading` + optional `.back-link`) and a
> content card (`paperLayer` + your controls). Counter-tilt the cards.
> Use `.btn-cta` / `.btn-secondary` / `.btn-destructive` for actions
> and `.btn-link` for in-flow text links. Use `.form-row` / `.form-control`
> for any form. If the screen needs a "see also" link to a different
> screen, attach a side post-it to the content card with the
> `position: absolute; left: calc(100% + 3rem); top: 50%` rule. Don't
> introduce new colours, tilts, button shapes, or paper-card variants
> without first updating this doc.
