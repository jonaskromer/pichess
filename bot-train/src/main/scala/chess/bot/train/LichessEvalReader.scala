package chess.bot.train

import java.io.{BufferedReader, InputStreamReader}
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import scala.jdk.CollectionConverters.*

import zio.*
import zio.stream.*

/** One parsed record from the shared Lichess-eval dataset (produced by
  * `nnue-train/extract_shards.py`). The single intermediate every trainer
  * reads — NNUE retrain + HCE distillation (value: [[whiteCp]]) and the
  * policy-ordering priors (move: [[best]] / [[mpv]]). */
final case class LichessEvalRow(
    fen: String,
    cp: Option[Int],          // White-POV centipawns (None ⇒ forced mate)
    mate: Option[Int],        // White-POV mate-in-N (None ⇒ not a mate)
    best: Option[String],     // SF best move (UCI) — the policy target
    depth: Int,               // search depth of the canonical (deepest) row
    knodes: Int,              // kilonodes — confidence weight for distillation
    mpv: List[(String, Int)], // top-K (move, White-POV cp) — SF move ranking
):
  /** A single numeric White-POV target, mates mapped to ±`mateCp`. */
  def whiteCp(mateCp: Int = 32000): Int =
    cp.getOrElse(if mate.exists(_ > 0) then mateCp else -mateCp)

/** Streaming reader for the shared dataset's gzipped TSV. Memory-flat: one
  * line at a time, so the 100M-row file is processed without loading it. */
object LichessEvalReader:

  private val NA = "\\N"

  /** Parse one TSV line into a row. `None` for the header / malformed lines. */
  def parseLine(line: String): Option[LichessEvalRow] =
    val f = line.split("\t", -1)
    if f.length < 7 || f(0) == "fen" then None
    else
      def opt(s: String): Option[String] =
        if s == NA || s.isEmpty then None else Some(s)
      val mpv =
        if f(6).isEmpty then Nil
        else
          f(6).split(",").toList.flatMap { tok =>
            tok.split(":", 2) match
              case Array(m, c) => c.toIntOption.map(m -> _)
              case _           => None
          }
      Some(
        LichessEvalRow(
          fen    = f(0),
          cp     = opt(f(1)).flatMap(_.toIntOption),
          mate   = opt(f(2)).flatMap(_.toIntOption),
          best   = opt(f(3)),
          depth  = f(4).toIntOption.getOrElse(0),
          knodes = f(5).toIntOption.getOrElse(0),
          mpv    = mpv,
        )
      )

  /** Stream rows from a `.tsv` or `.tsv.gz` file (header skipped). */
  def stream(path: Path): ZStream[Any, Throwable, LichessEvalRow] =
    ZStream
      .scoped {
        ZIO.fromAutoCloseable(ZIO.attempt {
          val raw = Files.newInputStream(path)
          val in  = if path.toString.endsWith(".gz") then GZIPInputStream(raw) else raw
          BufferedReader(InputStreamReader(in, "UTF-8"))
        })
      }
      .flatMap(br => ZStream.fromIterator(br.lines().iterator().asScala))
      .map(parseLine)
      .collectSome
