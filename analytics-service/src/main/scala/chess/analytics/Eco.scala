package chess.analytics

/** Best-effort opening *family* from the opening signature (the first few SANs
  * Spark captures). Not full ECO codes — a small curated prefix table covering
  * common families, longest/most-specific prefix first, falling back to
  * "Other". */
object Eco:

  private val families: List[(String, String)] = List(
    "e4 e5 Nf3 Nc6 Bb5" -> "Ruy Lopez",
    "e4 e5 Nf3 Nc6 Bc4" -> "Italian",
    "e4 c5"             -> "Sicilian",
    "e4 e6"             -> "French",
    "e4 c6"             -> "Caro-Kann",
    "e4 d6"             -> "Pirc",
    "e4 d5"             -> "Scandinavian",
    "e4 g6"             -> "Modern",
    "e4 e5"             -> "Open Game",
    "d4 Nf6 c4 g6"      -> "King's Indian",
    "d4 d5 c4"          -> "Queen's Gambit",
    "d4 Nf6"            -> "Indian Defense",
    "d4 d5"             -> "Closed Game",
    "c4"               -> "English",
    "Nf3"              -> "Réti",
    "e4"               -> "King's Pawn",
    "d4"               -> "Queen's Pawn"
  )

  def familyOf(opening: String): String =
    val o = opening.trim
    if o.isEmpty then "(no moves)"
    else
      families
        .collectFirst { case (p, fam) if o == p || o.startsWith(p + " ") => fam }
        .getOrElse("Other")
