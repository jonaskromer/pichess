package chess.webui.components

import com.raquo.laminar.api.L.*
import org.scalajs.dom

/** Reusable UI primitives — every screen composes from these helpers
  * rather than inventing per-screen markup. See `docs/design.md` §5
  * (component system) and §12.3 (catalogue) for the spec.
  *
  * Each helper carries its own Tailwind utility kit (layout, spacing,
  * typography) plus a single bespoke class for things utilities can't
  * express (clip-paths, drop-shadows reading CSS variables, the
  * marker-stripe gradient, tape pseudo-elements). The bespoke classes
  * are defined in `gateway/src/main/tailwind/bespoke.css`; if a helper
  * needs a new bespoke class, add it there.
  */
object Components:

  // --------------------------------------------------------------------------
  // Layout primitives
  // --------------------------------------------------------------------------

  /** Crumpled-paper SVG layer. The first child of any panel that wants a
    * paper backdrop (title/content card, board-paper, modal-dialog, …) —
    * absolutely fills its parent; siblings stack above via the panel's
    * `:not(.paper-layer)` stacking rule in bespoke.css.
    *
    * The `crumpled` argument is unused (the SVG is always crumpled) but
    * kept for source-compat with the prior call sites in `Main.scala`.
    * `grid` selects the gridded vs plain paper sprite. */
  def paperLayer(
      crumpled: Boolean = false,
      grid: Boolean = true
  ): HtmlElement =
    val _ = crumpled
    div(
      className := "paper-layer",
      // Crumple layer: the baked #crumple-tile heightmap tiled at a fixed px
      // size (constant fold scale), lit live by the per-theme --paper-filter
      // (.crumple-fill in bespoke.css). No viewBox → user units = CSS px, so the
      // tile doesn't stretch with the panel.
      svg.svg(
        svg.cls := "crumple-svg",
        svg.width := "100%",
        svg.height := "100%",
        svg.rect(
          svg.cls := "crumple-fill",
          svg.width := "100%",
          svg.height := "100%",
          svg.fill := "url(#crumple-tile)"
        )
      ),
      // Grid overlay drawn on top — still 600 viewBox + slice, so the ruling
      // scales with the panel exactly as before. Plain-paper surfaces skip it.
      if grid then
        svg.svg(
          svg.cls := "grid-svg",
          svg.viewBox := "0 0 600 600",
          svg.preserveAspectRatio := "xMidYMid slice",
          svg.use(svg.href := PaperGridHref)
        )
      else emptyNode
    )

  private val PaperGridHref = "#paper-grid-square"

  /** The canonical screen container. Used by every routed screen as the
    * outermost element — see design.md §6.1. The `.screen` class carries
    * the full layout/spacing kit via `@apply`; the `.screen-<name>`
    * modifier is reserved for per-screen overrides. */
  def screenLayout(
      name: String
  )(body: Modifier[HtmlElement]*): HtmlElement =
    div(
      className := s"screen screen-$name"
    ).amend(body*)

  /** Small paper card used for the screen heading. Tilts left, has a
    * torn bottom edge so the card below visually "rips away" from it.
    * See design.md §6.2. */
  def titleCard(body: Modifier[HtmlElement]*): HtmlElement =
    div(
      className := "title-card",
      paperLayer()
    ).amend(body*)

  /** Larger paper card holding the screen's controls. Counter-tilts
    * the title card. The right edge is intentionally clean (torn-left-
    * bottom) so a side post-it can attach. See design.md §6.3. */
  def contentCard(body: Modifier[HtmlElement]*): HtmlElement =
    div(
      className := "content-card",
      paperLayer()
    ).amend(body*)

  /** Small yellow sticky pinned to the page margin to the right of a
    * `contentCard`. Must be a child of the card it pins to (the bespoke
    * rule uses `position: absolute; left: calc(100% + …)` relative to
    * it). */
  def sidePostIt(body: Modifier[HtmlElement]*): HtmlElement =
    div(
      className := "side-postit"
    ).amend(body*)

  // --------------------------------------------------------------------------
  // Buttons
  // --------------------------------------------------------------------------

  /** Primary action for a screen / panel. Yellow post-it. One per local
    * context. */
  def ctaButton(
      label: String
  )(action: dom.MouseEvent => Unit): HtmlElement =
    button(
      typ := "button",
      className := "btn-cta",
      label,
      onClick --> { e => action(e) }
    )

  /** Secondary action (Cancel, Skip, Refresh). Cyan post-it. */
  def secondaryButton(
      label: String
  )(action: dom.MouseEvent => Unit): HtmlElement =
    button(
      typ := "button",
      className := "btn-secondary",
      label,
      onClick --> { e => action(e) }
    )

  /** Destructive action (Forfeit, Quit, Delete). Coral post-it. */
  def destructiveButton(
      label: String
  )(action: dom.MouseEvent => Unit): HtmlElement =
    button(
      typ := "button",
      className := "btn-destructive",
      label,
      onClick --> { e => action(e) }
    )

  /** Affirmative / proceed action in a confirmation modal — a newsprint
    * decision clip with a green marker (§5.1 / §5.9). A "stamped decision",
    * distinct from a casual post-it CTA. */
  def confirmButton(
      label: String
  )(action: dom.MouseEvent => Unit): HtmlElement =
    button(
      typ := "button",
      className := "btn-confirm",
      label,
      onClick --> { e => action(e) }
    )

  /** Cancel / back-out action in a confirmation modal — a newsprint
    * decision clip with a red marker. Pairs with [[confirmButton]]. */
  def cancelButton(
      label: String
  )(action: dom.MouseEvent => Unit): HtmlElement =
    button(
      typ := "button",
      className := "btn-cancel",
      label,
      onClick --> { e => action(e) }
    )

  /** Diagonal "photo-corner" tape strips for an overlay card (§4.3 / §5.7).
    * `corners` selects which of `tl`/`tr`/`bl`/`br` get a strip — default all
    * four (the modal look); pass a subset for a looser, varied taping (the help
    * panels each use a different combination so they don't read as stamped
    * copies). Render as a child of the non-clipping frame so the strips spill
    * past the torn paper edge. Decorative — `pointer-events: none`. */
  def tapeCorners(
      corners: Seq[String] = Seq("tl", "tr", "bl", "br")
  ): HtmlElement =
    div(
      className := "tape-corners",
      corners.map(c => span(className := s"tape-strip tape-$c"))
    )

  /** A lighter taping than [[tapeCorners]]: just two near-horizontal strips
    * across the top edge, as if the card were taped to the page by its top
    * corners. For small dialogs (e.g. the promotion picker) where four diagonal
    * corner strips read as fussy. */
  def tapeStripsTop(): HtmlElement =
    div(
      className := "tape-corners",
      span(className := "tape-strip tape-top-l"),
      span(className := "tape-strip tape-top-r")
    )

  /** In-flow text-style action (menu item, settings row link, mode tab,
    * doc link). Plain transparent button with marker-stripe hover.
    *
    * `extraClass` rides alongside `btn-link` for per-use tweaks — most
    * commonly a marker-colour override (e.g. `marker-green` swaps the
    * default yellow highlight for green on an affirmative action). */
  def linkButton(
      label: String,
      extraClass: String = ""
  )(action: dom.MouseEvent => Unit): HtmlElement =
    button(
      typ := "button",
      className := (if extraClass.isEmpty then "btn-link" else s"btn-link $extraClass"),
      label,
      onClick --> { e => action(e) }
    )

  /** Same visual as [[linkButton]] but rendered as a real `<a>` so
    * right-click / open-in-new-tab works. Use for `#hash` routes the
    * router can resolve (Docs, Help, etc.). */
  def linkAnchor(label: String, href0: String): HtmlElement =
    a(
      className := "btn-link",
      href := href0,
      label
    )

  /** Icon / single-glyph control (header back arrow, theme toggle,
    * close-X). Plain transparent button, slight opacity, lifts on
    * hover. */
  def iconButton(
      label: String
  )(action: dom.MouseEvent => Unit): HtmlElement =
    button(
      typ := "button",
      className := "btn-icon",
      label,
      onClick --> { e => action(e) }
    )

  /** Standard "← Back" link. Falls through to Start when there's no
    * history (fresh tab opened on a deep link). The `onStart` callback
    * lets the caller route to the start screen however the app's
    * navigation works. */
  def backLink(onStart: () => Unit): HtmlElement =
    iconButton("← Back") { _ =>
      if dom.window.history.length > 1 then dom.window.history.back()
      else onStart()
    }

  /** Brand peach sticker for the header-less routed screens — slapped into
    * the title card's top-left corner. Positioned absolute to the card (a
    * structural anchor, so it's out of layout flow without viewport-coordinate
    * fragility; §10.1). Decorative — the back link handles navigation. */
  def cornerPeach(): SvgElement =
    svg.svg(
      svg.viewBox := "-3 -3 43 44",
      svg.cls := "corner-peach",
      svg.use(svg.href := "/web/peach.svg#peach")
    )

  // --------------------------------------------------------------------------
  // Form controls
  // --------------------------------------------------------------------------

  /** Label + control row. The label sits to the left of the control
    * (or above on narrow screens). Wrap the control in this for any
    * form field that isn't a checkbox. */
  def formRow(
      labelText: String
  )(control: HtmlElement): HtmlElement =
    label(
      className := "form-row",
      span(className := "form-row-label", labelText),
      control
    )

  /** Checkbox + handwritten label, as one row. The native `<input>` is
    * kept in the tree (so the wrapping `<label>` still toggles it on
    * click + keyboard + screen reader) but visually hidden — the
    * `.checkbox-visual` sibling carries the doodle box and cross, which
    * react to the input's `:checked` state via the adjacent-sibling
    * selector in bespoke.css. */
  def checkboxRow(state: Var[Boolean], labelText: String): HtmlElement =
    label(
      className := "form-row form-row-checkbox",
      input(
        typ := "checkbox",
        checked <-- state.signal,
        onChange.mapToChecked --> state.writer
      ),
      span(className := "checkbox-visual"),
      span(className := "form-row-label", labelText)
    )

  /** Plain text input bound to a `Var[String]`. Returns the wrapper span
    * (which carries the hand-drawn underline pseudo) with the actual
    * `<input>` inside. Pass through any extra modifiers (e.g.
    * `placeholder := "…"`) via the variadic param — they land on the
    * `<input>`, not the wrapper. */
  def textInput(
      state: Var[String],
      extra: Modifier[Input]*
  ): HtmlElement =
    span(
      className := "text-field-wrap",
      input(
        typ := "text",
        className := "text-field",
        value <-- state.signal,
        onInput.mapToValue --> state.writer
      ).amend(extra*)
    )

  /** Number input clamped to `[min, max]`. Wrapper carries the hand-drawn
    * underline; two doodle arrow buttons inside the wrapper replace the
    * native spin buttons (which can't be themed cross-browser). */
  def numberInput(
      state: Var[Int],
      min: Int = 0,
      max: Int = Int.MaxValue
  ): HtmlElement =
    span(
      className := "text-field-wrap number-field-wrap",
      input(
        typ := "number",
        className := "text-field",
        value <-- state.signal.map(_.toString),
        onInput.mapToValue --> { s =>
          val parsed = s.toIntOption.getOrElse(min)
          state.set(parsed.max(min).min(max))
        }
      ),
      span(
        className := "number-stepper",
        button(
          typ := "button",
          className := "number-step number-step-up",
          aria.label := "Increment",
          onClick --> { _ => state.update(v => (v + 1).min(max)) }
        ),
        button(
          typ := "button",
          className := "number-step number-step-down",
          aria.label := "Decrement",
          onClick --> { _ => state.update(v => (v - 1).max(min)) }
        )
      )
    )

  /** Hand-drawn range slider bound to a `Var[Int]` over [min, max]. A native
    * `<input type=range>` (no JS library — see design.md): appearance reset,
    * the thumb + track restyled into a scribbled look via the `#hand-drawn`
    * filter (bespoke.css). The current value shows beside it; no fill (the
    * thumb position + value convey it). */
  def rangeSlider(state: Var[Int], min: Int, max: Int): HtmlElement =
    span(
      className := "range-slider-wrap",
      input(
        typ := "range",
        className := "range-slider",
        minAttr := min.toString,
        maxAttr := max.toString,
        stepAttr := "1",
        value <-- state.signal.map(_.toString),
        onInput.mapToValue --> { s => state.set(s.toIntOption.getOrElse(min)) }
      ),
      span(
        className := "range-slider-value",
        child.text <-- state.signal.map(_.toString)
      )
    )

  /** Single-select dropdown bound to a `Var[A]`. Options pair the
    * value with its display label. The optional `show` derives the
    * `<option>`'s `value` attribute from the option value (defaults
    * to `toString`); supply it when the value isn't a String / enum
    * with a stable `toString`. The native trigger is repainted (no
    * border, hand-drawn underline, doodle chevron); the popup menu
    * itself stays browser-default. */
  def selectInput[A](
      state: Var[A],
      options: Seq[(A, String)],
      show: A => String = (a: A) => a.toString
  ): HtmlElement =
    span(
      className := "text-field-wrap select-field-wrap",
      select(
        className := "text-field",
        value <-- state.signal.map(show),
        onChange.mapToValue --> { s =>
          options.find { case (v, _) => show(v) == s }.foreach { case (v, _) =>
            state.set(v)
          }
        },
        options.map { case (v, label0) =>
          option(value := show(v), label0)
        }
      )
    )

  // --------------------------------------------------------------------------
  // Composite patterns
  // --------------------------------------------------------------------------

  /** Tab strip — a row of `linkButton`-styled tabs separated by a
    * dotted hairline below. The active tab pins its marker stripe on;
    * disabled tabs are muted and not clickable.
    *
    * `tabs` is a sequence of `(value, label, enabled)`. */
  def tabStrip[A](
      state: Var[A],
      tabs: Seq[(A, String, Boolean)]
  ): HtmlElement =
    div(
      className := "tab-strip",
      tabs.map { case (value, label0, enabled) =>
        button(
          typ := "button",
          className := "btn-link tab-item",
          cls("is-active") <-- state.signal.map(_ == value).distinct,
          cls("is-disabled") := !enabled,
          disabled := !enabled,
          onClick --> { _ => if enabled then state.set(value) },
          // Inner span wraps the text so the scribbled oval (positioned
          // via the label's ::after) hugs the word width; the outer
          // <button> carries the per-tab underline segment via its own
          // ::after at the flex-track width.
          span(className := "tab-label", label0)
        )
      }
    )

  /** The single screen heading at the top of every routed screen — a headline
    * newspaper cutting. The `h1.screen-heading` drives the font-size + layout;
    * the cutting itself is the shared [[newsprintClip]] (heading variant). */
  def screenHeading(text: String): HtmlElement =
    h1(className := "screen-heading", newsprintClip(heading = true)(text))

  /** Small status pill — a torn-paper chip for lobby/game state (Open /
    * Full / Live …). `variant` selects the colour via a `status-<variant>`
    * modifier class defined in bespoke.css. */
  def statusBadge(label: String, variant: String = ""): HtmlElement =
    span(
      className := s"status-badge${if variant.nonEmpty then s" status-$variant" else ""}",
      label
    )

  /** A scrap of newsprint — a run of typewriter (Special Elite) text on
    * cream cut-paper with a jagged scissor-cut edge, lifted off the page by
    * a real drop-shadow. The single home for the "cut out of a newspaper and
    * pasted into the notebook" treatment (design.md §5.8 / §12.3). Every
    * newspaper-clipping surface routes through here: move-log tokens, the
    * end-game verdict clips + "Game Over" headline, the on-board result
    * banner, every screen / modal / help heading, scrap-table headers, help
    * inline code + block listings.
    *
    * The drop-shadow MUST live on an outer wrapper: `filter: drop-shadow`
    * applied to the clipped cutting itself is clipped to the same jagged
    * silhouette and disappears. So this renders
    * `span.newsprint-shadow > <cutting>` — the wrapper carries the shadow
    * (no clip-path of its own), the inner cutting carries the clip-path +
    * newsprint background.
    *
    * Three shapes via the flags:
    *   - default        an inline pill (`.code-inline`, near-straight cut)
    *   - `heading=true` a headline cutting (`.clip-heading`: all-around cut,
    *                     drop-cap, strong shadow). Wrap it in the semantic
    *                     `h1`/`h2`, which drives the font-size; the cutting
    *                     inherits it.
    *   - `block=true`   a multi-line `<pre>` listing (`.help-pre`, all-around
    *                     cut, strong block shadow + tape corners)
    *
    * `extraClass` lands on the inner cutting for per-use sizing / ink
    * overrides (e.g. `move-san`, `result-stat`, `result-clip`). */
  def newsprintClip(
      extraClass: String = "",
      block: Boolean = false,
      heading: Boolean = false
  )(body: Modifier[HtmlElement]*): HtmlElement =
    // Wrapper shadow strength scales with the cutting: `is-block` for the
    // multi-line listing, `is-strong` for headline cuttings, the plain
    // contact shadow for inline pills.
    val wrapCls =
      if block then "newsprint-shadow is-block"
      else if heading then "newsprint-shadow is-strong"
      else "newsprint-shadow"
    // Inner cutting: `.help-pre` is the block (<pre>) substrate; otherwise the
    // shared `.code-inline` pill, with `.clip-heading` switching it to the
    // all-around scissor cut + drop-cap for headline use.
    val innerBase =
      if block then "help-pre"
      else if heading then "code-inline clip-heading"
      else "code-inline"
    val innerCls =
      if extraClass.isEmpty then innerBase else s"$innerBase $extraClass"
    val inner =
      if block then pre(className := innerCls).amend(body*)
      else span(className := innerCls).amend(body*)
    span(className := wrapCls, inner)

  /** Ruled-ledger table — `.scrap-table` (design.md §5.8). Lives inside a paper
    * content card (no surface of its own). `columns` is a `(label, cellClass)`
    * spec: each label renders as a newspaper cutting (a header is a clipping,
    * not a sticker), and `cellClass` (`"col-num"` / `"col-status"` /
    * `"col-action"`, or `""` for a plain name column) carries alignment and
    * lines the header up with its body cells; an empty label is a bare header
    * (e.g. the action column). The caller supplies the body `<tr>` rows;
    * `emptyText` shows when there are none. */
  def scrapTable(
      columns: Seq[(String, String)],
      rows: Seq[HtmlElement],
      emptyText: String
  ): HtmlElement =
    if rows.isEmpty then div(className := "scrap-empty", emptyText)
    else
      table(
        className := "scrap-table",
        thead(
          tr(
            columns.map { case (label0, cellClass) =>
              th(
                className := cellClass,
                if label0.isEmpty then emptyNode
                else newsprintClip()(label0)
              )
            }
          )
        ),
        tbody(rows)
      )
