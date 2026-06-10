package chess.bot.train

import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

import zio.*
import zio.test.*

/** Round-trips the shared dataset format: the exact rows
  * `extract_shards.py` emits must parse back correctly (so Python writes /
  * Scala reads stay in lock-step). */
object LichessEvalReaderSpec extends ZIOSpecDefault:

  def spec = suite("LichessEvalReader")(
    test("parses a multi-PV row") {
      val r = LichessEvalReader
        .parseLine("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1\t30\t\\N\te2e4\t28\t1500\te2e4:30,d2d4:20")
        .get
      assertTrue(
        r.cp.contains(30),
        r.mate.isEmpty,
        r.best.contains("e2e4"),
        r.depth == 28,
        r.knodes == 1500,
        r.mpv == List("e2e4" -> 30, "d2d4" -> 20),
      )
    },
    test("parses a forced-mate row (cp = \\N) and maps whiteCp") {
      val r = LichessEvalReader.parseLine("4k3/8/8/8/8/8/8/4R1K1 w - - 0 1\t\\N\t2\te1e8\t30\t2000\te1e8:32000").get
      assertTrue(r.cp.isEmpty, r.mate.contains(2), r.whiteCp() == 32000, r.best.contains("e1e8"))
    },
    test("skips the header line") {
      assertTrue(LichessEvalReader.parseLine("fen\tcp\tmate\tbest\tdepth\tknodes\tmpv").isEmpty)
    },
    test("streams a gzipped TSV end-to-end (Python → Scala contract)") {
      val lines = List(
        "fen\tcp\tmate\tbest\tdepth\tknodes\tmpv",
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1\t30\t\\N\te2e4\t28\t1500\te2e4:30,d2d4:20",
        "4k3/8/8/8/8/8/8/4R1K1 w - - 0 1\t\\N\t2\te1e8\t30\t2000\te1e8:32000",
      )
      for
        tmp <- ZIO.attempt(Files.createTempFile("leval", ".tsv.gz"))
        _ <- ZIO.attempt {
               val w = BufferedWriter(
                 OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(tmp)), "UTF-8")
               )
               try lines.foreach(l => { w.write(l); w.write("\n") })
               finally w.close()
             }
        rows <- LichessEvalReader.stream(tmp).runCollect
        _    <- ZIO.attempt(Files.deleteIfExists(tmp))
      yield assertTrue(
        rows.size == 2,
        rows.head.best.contains("e2e4"),
        rows(1).mate.contains(2),
      )
    },
  )
