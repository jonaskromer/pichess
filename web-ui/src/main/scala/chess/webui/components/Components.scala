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
      svg.svg(
        svg.viewBox := "0 0 600 600",
        svg.preserveAspectRatio := "xMidYMid slice",
        svg.use(
          svg.href := (if grid then PaperGridHref else PaperGridlessHref)
        )
      )
    )

  private val PaperGridHref     = "#paper-crumpled-grid-square"
  private val PaperGridlessHref = "#paper-crumpled-square"

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

  /** In-flow text-style action (menu item, settings row link, mode tab,
    * doc link). Plain transparent button with marker-stripe hover. */
  def linkButton(
      label: String
  )(action: dom.MouseEvent => Unit): HtmlElement =
    button(
      typ := "button",
      className := "btn-link",
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

  /** The single screen heading at the top of every routed screen. The
    * font + size + colour live in the `.screen-heading` bespoke rule. */
  def screenHeading(text: String): HtmlElement =
    h1(className := "screen-heading", text)

  /** Small status pill — a torn-paper chip for lobby/game state (Open /
    * Full / Live …). `variant` selects the colour via a `status-<variant>`
    * modifier class defined in bespoke.css. */
  def statusBadge(label: String, variant: String = ""): HtmlElement =
    span(
      className := s"status-badge${if variant.nonEmpty then s" status-$variant" else ""}",
      label
    )
