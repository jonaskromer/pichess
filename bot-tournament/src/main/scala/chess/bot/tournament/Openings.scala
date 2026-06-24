package chess.bot.tournament

/** Pure helpers over the tournament server's cumulative UCI move log
  * (`"e2e4 e7e5 g1f3 …"`): incremental [[append]], the [[firstMove]], the
  * [[plies]] count, and ECO-style opening-[[family]] classification.
  *
  * The per-game `gameState` snapshot carries the full `moves` string, but each
  * `move` event carries only its own UCI, so the bridge folds them with
  * [[append]] into a running log it can classify when the game ends.
  */
object Openings:

  /** Append one UCI move to the running log (space-separated). */
  def append(log: String, uci: String): String =
    if log.isEmpty then uci else s"$log $uci"

  /** First move of the game (white's), or `None` for an empty log. */
  def firstMove(log: String): Option[String] = tokens(log).headOption

  /** Number of plies (half-moves) played. */
  def plies(log: String): Int = tokens(log).length

  private def tokens(log: String): Array[String] =
    log.trim.split("\\s+").filter(_.nonEmpty)

  /** Opening *family* from the UCI move prefix — a curated table mirroring the
    * analytics-service SAN classifier (`chess.analytics.Eco`) so tournament and
    * native-game dashboards share family names. Longest / most-specific prefix
    * wins; unknown → "Other", empty → "(no moves)".
    */
  def family(log: String): String =
    val normalised = tokens(log).mkString(" ").toLowerCase
    if normalised.isEmpty then "(no moves)"
    else
      families
        .collectFirst {
          case (p, fam) if normalised == p || normalised.startsWith(p + " ") =>
            fam
        }
        .getOrElse("Other")

  // UCI prefixes for the same families `chess.analytics.Eco` keys by SAN.
  private val families: List[(String, String)] = List(
    "e2e4 e7e5 g1f3 b8c6 f1b5" -> "Ruy Lopez",
    "e2e4 e7e5 g1f3 b8c6 f1c4" -> "Italian",
    "e2e4 c7c5"                -> "Sicilian",
    "e2e4 e7e6"                -> "French",
    "e2e4 c7c6"                -> "Caro-Kann",
    "e2e4 d7d6"                -> "Pirc",
    "e2e4 d7d5"                -> "Scandinavian",
    "e2e4 g7g6"                -> "Modern",
    "e2e4 e7e5"                -> "Open Game",
    "d2d4 g8f6 c2c4 g7g6"      -> "King's Indian",
    "d2d4 d7d5 c2c4"           -> "Queen's Gambit",
    "d2d4 g8f6"                -> "Indian Defense",
    "d2d4 d7d5"                -> "Closed Game",
    "c2c4"                     -> "English",
    "g1f3"                     -> "Réti",
    "e2e4"                     -> "King's Pawn",
    "d2d4"                     -> "Queen's Pawn"
  )
