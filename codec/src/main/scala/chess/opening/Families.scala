package chess.opening

/** Coarse opening *family* from a SAN move prefix — the canonical version of the
  * small table the analytics-service (`chess.analytics.Eco`) and bot-tournament
  * (`chess.bot.tournament.Openings`) classifiers each carry, so every surface
  * groups games under the same family names. Used as the fallback family when a
  * game matches no named [[EcoEntry]], and to fill [[Opening.family]].
  *
  * Inputs are already-normalised SAN tokens (check/mate glyphs stripped — see
  * [[EcoBook.normalize]]); the table is therefore glyph-free too. Longest /
  * most-specific prefix wins.
  */
object Families:

  private val table: Vector[(List[String], String)] = Vector(
    List("e4", "e5", "Nf3", "Nc6", "Bb5") -> "Ruy Lopez",
    List("e4", "e5", "Nf3", "Nc6", "Bc4") -> "Italian",
    List("e4", "c5")                       -> "Sicilian",
    List("e4", "e6")                       -> "French",
    List("e4", "c6")                       -> "Caro-Kann",
    List("e4", "d6")                       -> "Pirc",
    List("e4", "d5")                       -> "Scandinavian",
    List("e4", "g6")                       -> "Modern",
    List("e4", "e5")                       -> "Open Game",
    List("d4", "Nf6", "c4", "g6")          -> "King's Indian",
    List("d4", "d5", "c4")                 -> "Queen's Gambit",
    List("d4", "Nf6")                      -> "Indian Defense",
    List("d4", "d5")                       -> "Closed Game",
    List("c4")                             -> "English",
    List("Nf3")                            -> "Réti",
    List("e4")                             -> "King's Pawn",
    List("d4")                             -> "Queen's Pawn"
  ).sortBy(-_._1.length)

  /** Coarse family of a normalised SAN move list (empty → "(no moves)",
    * unknown → "Other").
    */
  def of(played: List[String]): String =
    if played.isEmpty then "(no moves)"
    else
      table
        .collectFirst {
          case (prefix, fam)
              if prefix.length <= played.length &&
                played.take(prefix.length) == prefix =>
            fam
        }
        .getOrElse("Other")
