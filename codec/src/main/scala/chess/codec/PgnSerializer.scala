package chess.codec

import java.time.format.DateTimeFormatter

import zio.{Clock, UIO}

import chess.model.board.GameStatus
import chess.model.piece.Color

object PgnSerializer:

  private val pgnDateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd")

  /** Serialize a plain move log + final status to a PGN string. The Date header
    * is read from `Clock.currentDateTime` so the function is pure given the ZIO
    * clock context — tests can control it via `TestClock`.
    */
  def serialize(
      moveLog: List[(Color, String)],
      status: GameStatus
  ): UIO[String] =
    serializeAnnotated(moveLog.map((c, san) => PgnMove(c, san)), status)

  /** Serialize an annotated move list + final status. `extraHeaders` are
    * overlaid on the default seven-tag roster (overriding by key, then
    * appending new tags like `ECO`/`Opening`/`TimeControl`). Per-move NAGs
    * (`$n`) and clock comments (`[%clk]`/`[%emt]`) are emitted in the movetext.
    */
  def serializeAnnotated(
      moves: List[PgnMove],
      status: GameStatus,
      extraHeaders: List[(String, String)] = Nil
  ): UIO[String] =
    serializeWithResult(moves, PgnCodec.encodeResult(status), extraHeaders)

  /** As [[serializeAnnotated]] but with an explicit PGN result token
    * (`1-0`/`0-1`/`1/2-1/2`/`*`) — used when the result is already known as a
    * token (e.g. the game archive) rather than a `GameStatus`.
    */
  def serializeWithResult(
      moves: List[PgnMove],
      result: String,
      extraHeaders: List[(String, String)] = Nil
  ): UIO[String] =
    Clock.currentDateTime.map { now =>
      val date = now.toLocalDate.format(pgnDateFormat)
      val defaults = List(
        "Event"  -> "πChess Game",
        "Site"   -> "Local",
        "Date"   -> date,
        "Round"  -> "1",
        "White"  -> "Player 1",
        "Black"  -> "Player 2",
        "Result" -> result
      )
      val header = mergeHeaders(defaults, extraHeaders)
        .map((k, v) => PgnCodec.encodeHeader(k, v))
        .mkString("\n")
      val movetext = formatMovetext(moves, result)
      s"$header\n\n$movetext"
    }

  /** Override default tags by key, then append extra tags not in the roster,
    * preserving their given order (so `ECO`/`Opening`/… follow `Result`).
    */
  private def mergeHeaders(
      defaults: List[(String, String)],
      extra: List[(String, String)]
  ): List[(String, String)] =
    val extraMap = extra.toMap
    val overridden = defaults.map((k, v) => k -> extraMap.getOrElse(k, v))
    val defaultKeys = defaults.iterator.map(_._1).toSet
    overridden ::: extra.filterNot((k, _) => defaultKeys.contains(k))

  private def formatMovetext(moves: List[PgnMove], result: String): String =
    val mv = moves.toVector
    val tokens = mv.zipWithIndex.map { case (m, i) =>
      val moveNo = i / 2 + 1
      // White always carries `N.`; black carries `N...` only when the previous
      // move was annotated (a comment/NAG broke the `N. white black` run) —
      // otherwise the bare SAN follows, e.g. `1. e4 e5`.
      val prefix =
        if m.color == Color.White then s"$moveNo. "
        else if i > 0 && mv(i - 1).annotated then s"$moveNo... "
        else ""
      val nag = m.nag.map(c => " " + PgnCodec.encodeNag(c)).getOrElse("")
      val comment = PgnCodec
        .encodeMoveComment(m.clockMs, m.emtMs)
        .map(" " + _)
        .getOrElse("")
      s"$prefix${m.san}$nag$comment"
    }
    val body = tokens.mkString(" ")
    if body.isEmpty then result else s"$body $result"
