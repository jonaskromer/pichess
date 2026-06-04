package chess.bot.train

import zio.test.*

/** Pins the source-quality numbers. Changing these is fine; the test
  * exists so a casual edit can't silently flip the ordering (Lichess
  * suddenly outweighing PGN Mentor would corrupt every tuner run).
  */
object CorpusSourceSpec extends ZIOSpecDefault:

  def spec = suite("CorpusSource")(
    test("quality is ordered: PgnMentor > Twic > EngineSelfPlay > Lichess") {
      assertTrue(
        CorpusSource.PgnMentor.quality      >  CorpusSource.Twic.quality,
        CorpusSource.Twic.quality           >  CorpusSource.EngineSelfPlay.quality,
        CorpusSource.EngineSelfPlay.quality >  CorpusSource.Lichess.quality,
      )
    },
    test("all qualities are in (0, 1]") {
      assertTrue(
        CorpusSource.values.forall(s => s.quality > 0.0f && s.quality <= 1.0f)
      )
    },
    test("names are unique and non-empty") {
      val names = CorpusSource.values.toList.map(_.name)
      assertTrue(
        names.distinct.size == names.size,
        names.forall(_.nonEmpty),
      )
    },
  )
