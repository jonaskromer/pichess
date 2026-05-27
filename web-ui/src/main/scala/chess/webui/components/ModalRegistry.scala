package chess.webui.components

import com.raquo.laminar.api.L.*
import org.scalajs.dom

/** Cross-modal coordinator for the page-level scroll lock.
  *
  * Each modal calls [[register]] once at App mount with a unique name
  * and its own open-state signal. The registry tracks the set of
  * currently-open modals and flips `body.modal-open` whenever the set
  * transitions from empty to non-empty (or back). The CSS rule
  * `body.modal-open { overflow: hidden; }` then locks the page beneath
  * any open dialog.
  *
  * This replaces the prior pattern of `Signal.combine(...)` over a
  * hard-coded list of modal Vars in `Main.scala` — every time a new
  * modal landed the combine had to grow. With the registry, a modal
  * is self-registering and the App() mount only needs to call
  * [[bindBodyClass]] once.
  *
  * See `docs/design.md` §5.7 + §12.4 for the spec.
  */
object ModalRegistry:

  /** Names of the modals currently open. Module-private so callers
    * can't bypass [[register]]. */
  private val openModals: Var[Set[String]] = Var(Set.empty)

  /** Subscribe a modal's open signal. Whenever it flips, update the
    * registry. Use `.distinct` here so a re-emission of the same value
    * (which Laminar can do for combined signals) doesn't toggle the
    * body class needlessly.
    *
    * `name` must be unique per modal — pick a short stable identifier
    * (e.g. `"promotion"`, `"loadGame"`). It's never user-visible.
    *
    * `owner` ties the subscription to whatever element scopes its
    * lifecycle — typically the App-root's mount context.
    */
  def register(
      name: String,
      openSignal: Signal[Boolean]
  )(using owner: Owner): Unit =
    openSignal.distinct.foreach { open =>
      openModals.update(s => if open then s + name else s - name)
    }

  /** Bind `body.modal-open` to the registry's nonEmpty signal. Call
    * once at App mount; the binding lives for the App's lifetime. */
  def bindBodyClass()(using owner: Owner): Unit =
    openModals.signal.map(_.nonEmpty).distinct.foreach { open =>
      val cl = dom.document.body.classList
      if open then cl.add("modal-open") else cl.remove("modal-open")
    }
