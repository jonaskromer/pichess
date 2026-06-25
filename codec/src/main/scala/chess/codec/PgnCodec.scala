package chess.codec

import chess.model.board.GameStatus
import chess.model.piece.Color

/** Co-located encode/decode for PGN-specific field types.
  *
  * Follows the same "single source of truth" principle as [[FenCodec]] and
  * [[JsonCodec]]: every value that appears in the wire format has its encode
  * and decode (or recognition) logic side-by-side.
  */
object PgnCodec:

  // ─── Result token ↔ GameStatus ────────────────────────────────────────────

  /** Encode a [[GameStatus]] as a PGN result token.
    *
    * Resignation collapses onto the same token as checkmate — the resigning
    * side simply loses, and PGN itself doesn't distinguish how the win happened
    * (any `[Termination]` annotation is a header, not a result token).
    */
  def encodeResult(status: GameStatus): String = status match
    case GameStatus.Playing                  => "*"
    case GameStatus.Checkmate(Color.White)   => "1-0"
    case GameStatus.Checkmate(Color.Black)   => "0-1"
    case GameStatus.Resignation(Color.White) => "1-0"
    case GameStatus.Resignation(Color.Black) => "0-1"
    case GameStatus.Draw(_)                  => "1/2-1/2"

  /** The set of valid PGN result tokens, derived from the encode mapping. Used
    * by [[PgnParser]] to filter result tokens out of movetext.
    */
  val resultTokens: Set[String] = Set("1-0", "0-1", "1/2-1/2", "*")

  // ─── Header encoding ─────────────────────────────────────────────────────

  private val headerPattern = """\[(\w+)\s+"([^"]*)"\]""".r

  def encodeHeader(key: String, value: String): String =
    s"""[$key "$value"]"""

  def decodeHeader(line: String): Option[(String, String)] =
    line match
      case headerPattern(key, value) => Some(key -> value)
      case _                         => None

  // ─── Clock / elapsed-move-time annotations ([%clk] / [%emt]) ───────────────
  //
  // Per-move clock data rides in PGN comments as the Lichess/chess.com de-facto
  // standard: `[%clk H:MM:SS]` (remaining clock after the move) and
  // `[%emt SS.s]` (elapsed move time). `%clk` is whole-second precision by
  // convention; the structured `PgnMove`/`MoveAnnotation` keep exact ms.

  /** Remaining clock → `H:MM:SS` (truncated to whole seconds; clamped at 0). */
  def encodeClock(ms: Long): String =
    val total = math.max(0L, ms) / 1000
    f"${total / 3600}%d:${(total % 3600) / 60}%02d:${total % 60}%02d"

  /** Parse a `H:MM:SS(.f)?` clock value to milliseconds. */
  def decodeClock(value: String): Option[Long] =
    value.trim.split(":") match
      case Array(h, m, s) =>
        for
          hh <- h.toLongOption
          mm <- m.toLongOption
          ms <- parseSeconds(s)
        yield (hh * 3600 + mm * 60) * 1000 + ms
      case _ => None

  /** Elapsed move time → seconds with one decimal place (clamped at 0). */
  def encodeEmt(ms: Long): String =
    val tenths = math.max(0L, ms) / 100
    if tenths % 10 == 0 then s"${tenths / 10}"
    else s"${tenths / 10}.${tenths % 10}"

  /** Parse a seconds value (`30`, `30.5`) to milliseconds. */
  def decodeEmt(value: String): Option[Long] = parseSeconds(value.trim)

  private def parseSeconds(token: String): Option[Long] =
    token.toDoubleOption.map(sec => math.round(sec * 1000))

  /** Build the `{...}` comment carrying a move's clock annotations (`[%emt]`
    * then `[%clk]`, Lichess order), or None if there's nothing to annotate.
    */
  def encodeMoveComment(
      clockMs: Option[Long],
      emtMs: Option[Long]
  ): Option[String] =
    val parts =
      emtMs.map(ms => s"[%emt ${encodeEmt(ms)}]").toList :::
        clockMs.map(ms => s"[%clk ${encodeClock(ms)}]").toList
    if parts.isEmpty then None else Some(parts.mkString("{", " ", "}"))

  private val clkPattern = """\[%clk\s+([^\]]+)\]""".r
  private val emtPattern = """\[%emt\s+([^\]]+)\]""".r

  /** Extract `[%clk ...]` from a comment body → milliseconds. */
  def extractClock(comment: String): Option[Long] =
    clkPattern.findFirstMatchIn(comment).flatMap(m => decodeClock(m.group(1)))

  /** Extract `[%emt ...]` from a comment body → milliseconds. */
  def extractEmt(comment: String): Option[Long] =
    emtPattern.findFirstMatchIn(comment).flatMap(m => decodeEmt(m.group(1)))

  /** A NAG code as its movetext token (`3` → `"$3"`). */
  def encodeNag(code: Int): String = "$" + code
