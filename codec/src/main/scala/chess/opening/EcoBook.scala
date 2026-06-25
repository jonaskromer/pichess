package chess.opening

import zio.*

/** The bundled ECO opening dataset, loaded from `/openings/eco.tsv`, with
  * longest-prefix name lookup.
  *
  * [[identify]] takes a game's SAN move list and returns the most-specific
  * named line whose moves are a prefix of the game (e.g. a Najdorf game →
  * "Sicilian Defense: Najdorf"), falling back to the coarse [[Families]] family
  * (then "Other"/"(no moves)") when no named line matches. Matching is on
  * normalised SAN (check/mate glyphs stripped) so engine-emitted `+`/`#` don't
  * defeat a match. Pure given the loaded entries.
  */
final class EcoBook private (val entries: Vector[EcoEntry]):

  // (entry, normalised moves), longest line first → first prefix hit is the
  // most specific.
  private val index: Vector[(EcoEntry, List[String])] =
    entries
      .map(e => e -> e.moves.map(EcoBook.normalize))
      .sortBy(-_._2.length)

  def identify(sanMoves: List[String]): Opening =
    if sanMoves.isEmpty then Opening.none
    else
      val played = sanMoves.map(EcoBook.normalize)
      index
        .collectFirst {
          case (entry, moves)
              if moves.length <= played.length &&
                played.take(moves.length) == moves =>
            Opening(Some(entry.eco), entry.name, Families.of(played), moves.length)
        }
        .getOrElse {
          val fam = Families.of(played)
          Opening(None, fam, fam, 0)
        }

object EcoBook:

  private val resourcePath = "/openings/eco.tsv"

  /** Strip check/mate glyphs so dataset and engine SAN compare equal. */
  def normalize(san: String): String = san.filterNot(c => c == '+' || c == '#')

  /** Parse the tab-separated dataset (`eco \t name \t san-moves`); blank lines
    * and `#` comments are skipped, malformed rows dropped.
    */
  def parse(tsv: String): Vector[EcoEntry] =
    tsv.linesIterator
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("#"))
      .flatMap { line =>
        line.split("\t") match
          case Array(eco, name, moves) =>
            Some(EcoEntry(eco.trim, name.trim, moves.trim.split("\\s+").toList))
          case _ => None
      }
      .toVector

  /** Build a book directly from entries (tests / custom datasets). */
  def fromEntries(entries: Vector[EcoEntry]): EcoBook = new EcoBook(entries)

  /** Load + parse the bundled ECO dataset from the classpath. */
  val load: Task[EcoBook] =
    ZIO.attempt {
      val source = scala.io.Source.fromInputStream(
        getClass.getResourceAsStream(resourcePath),
        "UTF-8"
      )
      try new EcoBook(parse(source.mkString))
      finally source.close()
    }
