package chess.webui

import chess.api.{BoardStateDto, MoveEntryDto, MoveRequest, SquareDto}
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import zio.json.*

object Main:

  def main(args: Array[String]): Unit =
    renderOnDomContentLoaded(dom.document.getElementById("app"), App())

  private case class PendingPromotion(from: String, to: String)

  // --------------------------------------------------------------------------
  // Reactive state
  // --------------------------------------------------------------------------

  private val stateVar: Var[Option[BoardStateDto]] = Var(None)
  private val dragSourceVar: Var[Option[String]] = Var(None)
  private val pendingPromotionVar: Var[Option[PendingPromotion]] = Var(None)
  private val toastVar: Var[Option[String]] = Var(None)
  private val goodbyeVar: Var[Boolean] = Var(false)

  private val whitePromotions = List(
    "Q" -> "♕",
    "R" -> "♖",
    "B" -> "♗",
    "N" -> "♘",
  )
  private val blackPromotions = List(
    "Q" -> "♛",
    "R" -> "♜",
    "B" -> "♝",
    "N" -> "♞",
  )

  // --------------------------------------------------------------------------
  // Top-level component
  // --------------------------------------------------------------------------

  private def App(): HtmlElement =
    div(
      onMountCallback { _ =>
        fetchState()
        connectEvents()
      },
      child <-- goodbyeVar.signal.map {
        case true  => goodbyeScreen()
        case false => mainUi()
      },
    )

  private def goodbyeScreen(): HtmlElement =
    div(
      styleAttr := "display:flex;justify-content:center;align-items:center;" +
        "height:100vh;font-size:24px;color:#f7a072;",
      "Goodbye!",
    )

  private def mainUi(): HtmlElement =
    div(
      className := "app",
      boardArea(),
      sidebar(),
      promotionOverlay(),
      toastElement(),
    )

  // --------------------------------------------------------------------------
  // Board
  // --------------------------------------------------------------------------

  private def boardArea(): HtmlElement =
    div(
      className := "board-area",
      div(
        className := "board-wrapper",
        rankLabels(),
        board(),
        fileLabels(),
      ),
    )

  private def rankLabels(): HtmlElement =
    div(
      className := "rank-labels",
      (8 to 1 by -1).toList.map(r => div(r.toString)),
    )

  private def fileLabels(): HtmlElement =
    div(
      className := "file-labels",
      ('a' to 'h').toList.map(c => div(c.toString)),
    )

  private def board(): HtmlElement =
    div(
      className := "board",
      children <-- stateVar.signal.map {
        case None    => List.empty
        case Some(s) => s.squares.map(renderSquare(s, _))
      },
    )

  private def renderSquare(
      state: BoardStateDto,
      sq: SquareDto,
  ): HtmlElement =
    val isChecked = state.checkedKingPos.contains(sq.pos)
    val squareClasses =
      s"square ${sq.squareColor}" + (if isChecked then " in-check" else "")
    div(
      className := squareClasses,
      dataAttr("pos") := sq.pos,
      onDragOver.preventDefault --> { _ => () },
      onDrop.preventDefault --> { _ => handleDrop(sq.pos, state) },
      sq.piece.map { glyph =>
        span(
          className := s"piece ${sq.pieceColor.getOrElse("")}-piece",
          draggable := true,
          onDragStart --> { e => handleDragStart(sq.pos, e) },
          onDragEnd --> { _ => dragSourceVar.set(None) },
          glyph,
        )
      },
    )

  // --------------------------------------------------------------------------
  // Sidebar
  // --------------------------------------------------------------------------

  private def sidebar(): HtmlElement =
    div(
      className := "sidebar",
      h1(className := "title", "piChess"),
      turnIndicator(),
      moveLogContainer(),
      controls(),
    )

  private def turnIndicator(): HtmlElement =
    div(
      className := "turn-indicator",
      children <-- stateVar.signal.map {
        case Some(s) =>
          val name = if s.activeColor == "white" then "White" else "Black"
          List(
            div(className := s"turn-dot ${s.activeColor}"),
            span(s"$name to move"),
          )
        case None => List.empty
      },
    )

  private def moveLogContainer(): HtmlElement =
    div(
      className := "move-log-container",
      h2(className := "section-title", "Moves"),
      div(
        className := "move-log",
        children <-- stateVar.signal.map {
          case None    => List.empty
          case Some(s) => renderMoveLog(s.moveLog)
        },
      ),
    )

  private def renderMoveLog(moves: List[MoveEntryDto]): List[HtmlElement] =
    moves
      .grouped(2)
      .zipWithIndex
      .map { case (pair, idx) =>
        val cells = List(
          span(className := "move-number", s"${idx + 1}."),
          span(className := "move-san", pair.head.san),
        ) ++ pair.drop(1).map(m => span(className := "move-san", m.san))
        div(className := "move-row", cells)
      }
      .toList

  private def controls(): HtmlElement =
    val moveInputVar = Var("")
    div(
      className := "controls",
      form(
        idAttr := "moveForm",
        onSubmit.preventDefault --> { _ =>
          val v = moveInputVar.now().trim
          if v.nonEmpty then
            postMove(v)
            moveInputVar.set("")
        },
        input(
          tpe          := "text",
          idAttr       := "moveInput",
          placeholder  := "e.g. e2e4 or Nf3",
          autoComplete := "off",
          spellCheck   := false,
          controlled(
            value <-- moveInputVar.signal,
            onInput.mapToValue --> moveInputVar,
          ),
        ),
        button(tpe := "submit", "Move"),
      ),
      div(
        className := "btn-row",
        button(
          className := "secondary-btn",
          onClick --> { _ => postSimple("/api/undo") },
          "Undo",
        ),
        button(
          className := "secondary-btn",
          onClick --> { _ => postSimple("/api/redo") },
          "Redo",
        ),
      ),
      div(
        className := "btn-row",
        button(
          className := "secondary-btn",
          onClick --> { _ => postSimple("/api/draw") },
          "Draw",
        ),
        button(
          className := "secondary-btn",
          onClick --> { _ => postSimple("/api/new") },
          "New Game",
        ),
        button(
          className := "quit-btn",
          onClick --> { _ => postSimple("/api/quit") },
          "Quit",
        ),
      ),
    )

  // --------------------------------------------------------------------------
  // Overlays
  // --------------------------------------------------------------------------

  private def toastElement(): HtmlElement =
    div(
      idAttr := "toast",
      className <-- toastVar.signal.map(t =>
        if t.isDefined then "toast visible" else "toast"
      ),
      child.text <-- toastVar.signal.map(_.getOrElse("")),
    )

  private def promotionOverlay(): HtmlElement =
    div(
      idAttr := "promotionOverlay",
      className <-- pendingPromotionVar.signal.map(p =>
        if p.isDefined then "promotion-overlay visible" else "promotion-overlay"
      ),
      div(
        idAttr    := "promotionDialog",
        className := "promotion-dialog",
        children <-- pendingPromotionVar.signal
          .combineWith(stateVar.signal)
          .map {
            case (Some(p), Some(s)) => promotionChoices(p, s)
            case _                  => List.empty
          },
      ),
    )

  private def promotionChoices(
      p: PendingPromotion,
      state: BoardStateDto,
  ): List[HtmlElement] =
    state.squares.find(_.pos == p.from) match
      case None => List.empty
      case Some(sq) =>
        val pieces =
          if sq.pieceColor.contains("white") then whitePromotions
          else blackPromotions
        val colorClass = s"${sq.pieceColor.getOrElse("")}-piece"
        pieces.map { case (key, glyph) =>
          div(
            className := s"promotion-choice $colorClass",
            onClick --> { _ =>
              pendingPromotionVar.set(None)
              postMove(s"${p.from} ${p.to}=$key")
            },
            glyph,
          )
        }

  // --------------------------------------------------------------------------
  // HTTP + SSE
  // --------------------------------------------------------------------------

  private def fetchState(): Unit =
    dom
      .fetch("/api/state")
      .toFuture
      .flatMap(_.text().toFuture)
      .foreach(body => decodeState(body))

  private def connectEvents(): Unit =
    val source = new dom.EventSource("/api/events")
    source.addEventListener(
      "state",
      (e: dom.MessageEvent) => decodeState(e.data.asInstanceOf[String]),
    )
    source.addEventListener(
      "quit",
      (_: dom.MessageEvent) =>
        source.close()
        goodbyeVar.set(true),
    )

  private def decodeState(body: String): Unit =
    body.fromJson[BoardStateDto] match
      case Right(state) => stateVar.set(Some(state))
      case Left(err)    => showToast(s"Bad state payload: $err")

  private def postMove(move: String): Unit =
    val body = MoveRequest(move).toJson
    val init = js.Dynamic
      .literal(
        method  = "POST",
        headers = js.Dictionary("Content-Type" -> "application/json"),
        body    = body,
      )
      .asInstanceOf[dom.RequestInit]
    fetchAndHandleError("/api/move", init)

  private def postSimple(path: String): Unit =
    val init = js.Dynamic.literal(method = "POST").asInstanceOf[dom.RequestInit]
    fetchAndHandleError(path, init)

  private def fetchAndHandleError(path: String, init: dom.RequestInit): Unit =
    dom
      .fetch(path, init)
      .toFuture
      .flatMap(_.text().toFuture)
      .foreach { body =>
        // /api/move returns BoardStateDto on success; error routes (and
        // failed moves) return ErrorDto. Try state first — if that fails,
        // try to surface an error message.
        body.fromJson[BoardStateDto] match
          case Right(_) => () // SSE will push the new state; nothing to do.
          case Left(_) =>
            body.fromJson[chess.api.ErrorDto] match
              case Right(e) => showToast(e.error)
              case Left(_)  => () // e.g. /api/quit returns {"quit":true}
      }

  private def showToast(msg: String): Unit =
    toastVar.set(Some(msg))
    dom.window.setTimeout(() => toastVar.set(None), 3000)

  // --------------------------------------------------------------------------
  // Drag + drop
  // --------------------------------------------------------------------------

  private def handleDragStart(pos: String, e: dom.DragEvent): Unit =
    dragSourceVar.set(Some(pos))
    e.dataTransfer.effectAllowed =
      "move".asInstanceOf[dom.DataTransferEffectAllowedKind]
    e.dataTransfer.setData("text/plain", "")

  private def handleDrop(target: String, state: BoardStateDto): Unit =
    dragSourceVar.now() match
      case None                       => ()
      case Some(src) if src == target => dragSourceVar.set(None)
      case Some(src) =>
        if isPawnPromotion(src, target, state) then
          pendingPromotionVar.set(Some(PendingPromotion(src, target)))
        else postMove(s"$src $target")
        dragSourceVar.set(None)

  private def isPawnPromotion(
      from: String,
      to: String,
      state: BoardStateDto,
  ): Boolean =
    state.squares.find(_.pos == from).flatMap(_.piece) match
      case Some(p) if p == "♙" || p == "♟" =>
        val row = to.charAt(1)
        row == '8' || row == '1'
      case _ => false
