package chess.codec

import chess.model.board.{DrawReason, GameStatus}
import chess.model.piece.Color

/** Co-located encode/decode for PGN-specific field types.
  *
  * Follows the same "single source of truth" principle as [[FenCodec]] and
  * [[JsonCodec]]: every value that appears in the wire format has its encode
  * and decode (or recognition) logic side-by-side.
  */
object PgnCodec:

  // ─── Result token ↔ GameStatus ────────────────────────────────────────────

  /** Encode a [[GameStatus]] as a PGN result token. */
  def encodeResult(status: GameStatus): String = status match
    case GameStatus.Playing                => "*"
    case GameStatus.Checkmate(Color.White) => "1-0"
    case GameStatus.Checkmate(Color.Black) => "0-1"
    case GameStatus.Draw(_)                => "1/2-1/2"

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
