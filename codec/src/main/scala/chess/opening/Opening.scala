package chess.opening

/** One entry of the bundled ECO dataset (`/openings/eco.tsv`): an ECO code, a
  * human name, and the SAN move sequence that defines the line.
  */
final case class EcoEntry(eco: String, name: String, moves: List[String])

/** The opening identified for a game.
  *
  *   - `eco`/`name` come from the most-specific matching [[EcoEntry]] (e.g.
  *     `B90` / "Sicilian Defense: Najdorf"), or `None`/the family name when the
  *     game matched no named line;
  *   - `family` is the coarse family (e.g. "Sicilian"), always present, shared
  *     with the analytics/tournament classifiers so dashboards line up;
  *   - `plyMatched` is how many plies of the named line matched (0 when only the
  *     family — or nothing — was recognised).
  */
final case class Opening(
    eco: Option[String],
    name: String,
    family: String,
    plyMatched: Int
):
  /** Display label, e.g. "B90 · Sicilian Defense: Najdorf" or "Sicilian". */
  def label: String = eco.fold(name)(code => s"$code · $name")

object Opening:
  val none: Opening = Opening(None, "(no moves)", "(no moves)", 0)
