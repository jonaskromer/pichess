package chess.opening

import zio.*
import zio.test.*

import chess.model.GameError
import chess.model.board.GameState
import chess.model.rules.Game
import chess.notation.{MoveParser, SanSerializer}

/** Validates the bundled `eco.tsv` against the rules engine: every line must be
  * legal from the initial position and re-serialize to the same SAN (up to the
  * `+`/`#` glyphs the matcher normalises away). This guarantees the dataset's
  * SAN matches engine-emitted SAN, so [[EcoBook.identify]] actually matches real
  * games. Also smoke-tests identification on the loaded book.
  */
object EcoDatasetSpec extends ZIOSpecDefault:

  /** Replay a SAN line, returning the engine's canonical SAN for each move. */
  private def replayCanonical(moves: List[String]): IO[GameError, List[String]] =
    ZIO
      .foldLeft(moves)((GameState.initial, List.empty[String])) {
        case ((state, acc), san) =>
          for
            move  <- MoveParser.parse(san, state)
            canon <- SanSerializer.toSan(move, state)
            next  <- Game.applyMove(state, move)
          yield (next, acc :+ canon)
      }
      .map(_._2)

  private def check(e: EcoEntry): UIO[Option[String]] =
    replayCanonical(e.moves)
      .map { canon =>
        val got      = canon.map(EcoBook.normalize)
        val expected = e.moves.map(EcoBook.normalize)
        Option.when(got != expected)(
          s"${e.eco} ${e.name}: expected $expected got $got"
        )
      }
      .catchAll(err => ZIO.succeed(Some(s"${e.eco} ${e.name}: $err")))

  def spec = suite("EcoDataset")(
    test("every line is legal and canonical engine SAN") {
      for
        bk       <- EcoBook.load
        problems <- ZIO.foreach(bk.entries.toList)(check)
      yield assertTrue(
        bk.entries.nonEmpty,
        problems.flatten == List.empty[String]
      )
    },
    test("identifies real openings from the loaded book") {
      val najdorf = "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6".split(" ").toList
      val berlin  = "e4 e5 Nf3 Nc6 Bb5 Nf6".split(" ").toList
      val qgd     = "d4 d5 c4 e6".split(" ").toList
      for bk <- EcoBook.load
      yield assertTrue(
        bk.identify(najdorf).eco == Some("B90"),
        bk.identify(najdorf).name.contains("Najdorf"),
        bk.identify(najdorf).family == "Sicilian",
        bk.identify(berlin).name.contains("Berlin"),
        bk.identify(qgd).family == "Queen's Gambit",
        bk.identify(List("Nc3", "d5")).eco == None
      )
    }
  )
