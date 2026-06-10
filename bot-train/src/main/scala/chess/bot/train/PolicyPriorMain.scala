package chess.bot.train

import java.nio.file.{Files, Paths}

import zio.*

import chess.bot.engine.PolicyPrior

/** Offline builder for the SF-distilled move-ordering priors
  * (`/policy-prior.bin`, consumed by `AlphaBetaSearch` when
  * `policyOrderingEnabled`).
  *
  * Streams the shared Lichess-eval TSV (`nnue-train/extract_shards.py` →
  * [[LichessEvalReader]]), counts how often Stockfish's best move was each
  * `from → to`, log-normalises the counts to `[0, maxBonus]`, and writes the
  * baked 64×64 table into the bot-engine resources. Log scaling stops a
  * hugely-common opening move from dwarfing the merely-good ones.
  *
  * Run:
  * {{{
  *   sbt 'botTrain/runMain chess.bot.train.PolicyPriorMain <tsv> [out.bin] [maxBonus]'
  * }}}
  */
object PolicyPriorMain extends ZIOAppDefault:

  private val DefaultOut      = "bot-engine/src/main/resources/policy-prior.bin"
  private val DefaultMaxBonus = 20_000

  /** UCI square (`"e2"` at offset `i`) → LERF index `file + rank*8`, or -1 if
    * malformed. Matches `MoveInt`'s from/to indexing + the history table. */
  def sq(uci: String, i: Int): Int =
    if i + 1 >= uci.length then -1
    else
      val f = uci.charAt(i) - 'a'
      val r = uci.charAt(i + 1) - '1'
      if f < 0 || f > 7 || r < 0 || r > 7 then -1 else f + r * 8

  /** Tally one best move's `from → to` into `counts` (no-op if malformed). */
  def accumulate(counts: Array[Long], uci: String): Unit =
    if uci.length >= 4 then
      val from = sq(uci, 0)
      val to   = sq(uci, 2)
      if from >= 0 && to >= 0 then counts(from * 64 + to) += 1

  /** Log-normalise raw from→to counts to a `[0, maxBonus]` bonus table. */
  def normalize(counts: Array[Long], maxBonus: Int): Array[Int] =
    val table = new Array[Int](PolicyPrior.Size)
    var maxC  = 0L
    var i     = 0
    while i < counts.length do
      if counts(i) > maxC then maxC = counts(i)
      i += 1
    if maxC > 0 then
      val denom = math.log1p(maxC.toDouble)
      i = 0
      while i < table.length do
        table(i) = math.round(maxBonus * math.log1p(counts(i).toDouble) / denom).toInt
        i += 1
    table

  def run: ZIO[ZIOAppArgs, Throwable, Unit] =
    for
      args <- getArgs
      tsv  = args.headOption
               .orElse(sys.env.get("PICHESS_POLICY_TSV"))
               .getOrElse("nnue-train/data/lichess-eval.tsv.gz")
      out  = args.lift(1).getOrElse(DefaultOut)
      maxB = args.lift(2).flatMap(_.toIntOption).getOrElse(DefaultMaxBonus)
      counts = new Array[Long](PolicyPrior.Size)
      n <- LichessEvalReader
             .stream(Paths.get(tsv))
             .runFoldZIO(0L) { (n, row) =>
               ZIO.succeed {
                 row.best.foreach(accumulate(counts, _))
                 n + 1
               }
             }
      _ <- ZIO.attempt {
             val table   = normalize(counts, maxB)
             val p       = Paths.get(out)
             Option(p.getParent).foreach(Files.createDirectories(_))
             Files.write(p, PolicyPrior.toBytes(table))
             val nonzero = table.count(_ > 0)
             println(s"policy-prior: rows=$n  nonzero from→to=$nonzero/${PolicyPrior.Size}  -> $out")
           }
    yield ()
