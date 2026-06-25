package chess.opening

import zio.test.*

/** Pure tests for the ECO matcher: parsing, normalisation, longest-prefix
  * naming, and the coarse-family fallback. Dataset legality/canonicality is
  * covered separately by [[EcoDatasetSpec]] (which needs the rules engine).
  */
object EcoBookSpec extends ZIOSpecDefault:

  private val book = EcoBook.fromEntries(
    Vector(
      EcoEntry("B20", "Sicilian Defense", List("e4", "c5")),
      EcoEntry(
        "B90",
        "Sicilian Defense: Najdorf",
        "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6".split(" ").toList
      ),
      EcoEntry("E11", "Bogo-Indian Defense", "d4 Nf6 c4 e6 Nf3 Bb4+".split(" ").toList)
    )
  )

  def spec = suite("EcoBook")(
    test("normalize strips check/mate glyphs only") {
      assertTrue(
        EcoBook.normalize("Bb4+") == "Bb4",
        EcoBook.normalize("Qh7#") == "Qh7",
        EcoBook.normalize("exd5") == "exd5",
        EcoBook.normalize("O-O") == "O-O"
      )
    },
    test("parse keeps valid rows, drops comments / blanks / malformed") {
      val tsv =
        "# a comment\n\nB20\tSicilian Defense\te4 c5\nbroken row\nA04\tRéti Opening\tNf3\n"
      val entries = EcoBook.parse(tsv)
      assertTrue(
        entries.length == 2,
        entries.head == EcoEntry("B20", "Sicilian Defense", List("e4", "c5")),
        entries(1).moves == List("Nf3")
      )
    },
    test("identifies the most-specific named line (longest prefix wins)") {
      val najdorf = "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6".split(" ").toList
      val op = book.identify(najdorf)
      assertTrue(
        op.eco == Some("B90"),
        op.name == "Sicilian Defense: Najdorf",
        op.family == "Sicilian",
        op.plyMatched == 10
      )
    },
    test("falls back to a shorter named line when the deep one diverges") {
      val op = book.identify(List("e4", "c5", "Nf3", "e6"))
      assertTrue(op.eco == Some("B20"), op.plyMatched == 2)
    },
    test("matches across check glyphs via normalisation") {
      // Game SAN carries the engine's '+'; the dataset line stored 'Bb4+' too,
      // both normalise away so the match still lands.
      val game = "d4 Nf6 c4 e6 Nf3 Bb4+".split(" ").toList
      assertTrue(book.identify(game).name == "Bogo-Indian Defense")
    },
    test("an empty game is Opening.none") {
      assertTrue(book.identify(Nil) == Opening.none)
    },
    test("an unmatched game falls back to the coarse family / Other") {
      // French (in the family table, not in this small book) → family fallback;
      // 1.Nc3 is in neither → Other.
      val french = book.identify(List("e4", "e6"))
      val dunst = book.identify(List("Nc3", "d5"))
      assertTrue(
        french.eco == None,
        french.name == "French",
        french.family == "French",
        dunst.eco == None,
        dunst.family == "Other"
      )
    },
    test("label renders eco + name, or bare name") {
      assertTrue(
        Opening(Some("B90"), "Sicilian Defense: Najdorf", "Sicilian", 10).label ==
          "B90 · Sicilian Defense: Najdorf",
        Opening(None, "Sicilian", "Sicilian", 0).label == "Sicilian"
      )
    },
    test("Families.of: empty, a family, and Other") {
      assertTrue(
        Families.of(Nil) == "(no moves)",
        Families.of(List("e4", "c5", "Nf3")) == "Sicilian",
        Families.of(List("Nc3")) == "Other"
      )
    }
  )
