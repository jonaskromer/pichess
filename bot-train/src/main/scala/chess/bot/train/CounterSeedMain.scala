package chess.bot.train

import java.nio.ByteBuffer
import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import zio.*

import chess.codec.PgnParser
import chess.model.board.MoveInt

/** Precompute a Counter-Move Heuristic seed table from the
  * ingested PGN corpus and persist it as a binary resource.
  *
  * The CMH wants "for this opponent move (from, to), what's the
  * canonical refutation?" The runtime engine learns that from its
  * own β-cutoffs, starting empty each search. A baked seed from
  * master games answers the same question with millions of
  * data-points before move 1, so the search starts with strong
  * counter-suggestions for every common opponent move.
  *
  * Algorithm:
  *   1. Walk every game in every PGN file under
  *      `PICHESS_CORPUS_DIR` (defaults to `/tmp/chess-corpus`).
  *   2. For each consecutive `(prev_move, reply)` pair, increment
  *      `counts[prev_move.from][prev_move.to][reply_encoded] += 1`.
  *   3. After all games, pick the modal reply per `(from, to)`
  *      key. Ties broken by `MoveInt` value (deterministic).
  *   4. Write the resulting 64×64 `Int` table to
  *      `bot-engine/src/main/resources/counter-seed.bin` as 16384
  *      bytes (big-endian Int per cell, -1 for "no data").
  *
  * Run once per major corpus refresh:
  * {{{
  *   sbt 'botTrain/runMain chess.bot.train.CounterSeedMain'
  * }}}
  */
object CounterSeedMain extends ZIOAppDefault:

  private val NoReply = -1

  private val defaultCorpusRoot = "/tmp/chess-corpus"
  // Resolved relative to project root, but sbt's `fork = true`
  // changes CWD to the subproject. Detect via env or fall back to
  // a path absolute from the parent project root. Same dodge used
  // in `TrainMain` to avoid writing the seed under
  // `bot-train/bot-engine/...`. See [[buildSeed]] for the
  // user-visible "Wrote X" log so the destination is obvious.
  private def defaultOutputPath(): String =
    val cwd = java.nio.file.Paths.get("").toAbsolutePath
    val projectRoot =
      if cwd.endsWith("bot-train") then cwd.getParent
      else cwd
    projectRoot.resolve("bot-engine/src/main/resources/counter-seed.bin").toString

  def run: ZIO[ZIOAppArgs & Scope, Any, Any] = program

  private def program: ZIO[Any, Throwable, Unit] =
    for
      corpusDir <- ZIO.succeed(
                     sys.env.getOrElse("PICHESS_CORPUS_DIR", defaultCorpusRoot)
                   )
      outputPath = sys.env.getOrElse("PICHESS_COUNTER_SEED_OUT", defaultOutputPath())
      _      <- ZIO.logInfo(s"Counter-seed scan under: $corpusDir")
      pgns   <- listPgnFiles(corpusDir)
      _      <- ZIO.logInfo(s"Found ${pgns.size} PGN files")
      seed   <- buildSeed(pgns)
      filled  = seed.count(_ != NoReply)
      _      <- ZIO.logInfo(s"Filled $filled / ${seed.length} (from,to) keys")
      _      <- writeSeed(seed, Paths.get(outputPath))
      _      <- ZIO.logInfo(s"Wrote $outputPath")
    yield ()

  private def listPgnFiles(root: String): UIO[List[Path]] =
    ZIO
      .attempt {
        val p = Paths.get(root)
        if !Files.isDirectory(p) then Nil
        else
          // Walk the whole tree — corpus uses sub-dirs per source.
          val it = Files.walk(p).iterator.asScala
          try it.toList.filter(f => f.toString.endsWith(".pgn")).sortBy(_.toString)
          finally Files.walk(p).close()
      }
      .orElseSucceed(Nil)

  /** Streaming accumulate: walk each file, parse each game, fold
    * `(prev_move, reply)` pairs into a per-(from,to)-keyed map of
    * reply counts. Per-file fold keeps the in-memory aggregate
    * manageable — for ~1M games and ~4M move pairs the final map
    * has ~4096 outer entries × N inner reply variants ≈ few MB. */
  private def buildSeed(files: List[Path]): UIO[Array[Int]] =
    ZIO
      .foldLeft(files)(emptyAcc) { (acc, file) =>
        ZIO
          .attemptBlocking(
            // ISO-8859-1: PGN sources frequently embed Latin-1
            // characters in player names (`Petrosián`, `Reșko`).
            // `Files.readString` defaults to UTF-8 and throws
            // `MalformedInputException` on such files, dropping
            // the entire game collection. Latin-1 maps any single
            // byte to a char without complaint, which lets the
            // PGN parser still see the SAN tokens.
            Files.readString(file, java.nio.charset.StandardCharsets.ISO_8859_1)
          )
          .flatMap(processFile(_, acc))
          .catchAll(err =>
            ZIO.logWarning(s"skip ${file.getFileName}: ${err.getMessage}").as(acc)
          )
      }
      .map(foldModal)

  private def processFile(
      content: String,
      acc: Acc,
  ): UIO[Acc] =
    val games = PgnIngest.splitGames(content)
    ZIO.foldLeft(games)(acc) { (a, gamePgn) =>
      PgnParser
        .parse(gamePgn)
        .foldZIO(
          _    => ZIO.succeed(a),
          game => ZIO.succeed(accumulateGame(game, a)),
        )
    }

  /** Per (from*64 + to) key, a `move -> count` reply tally. Mutable
    * for build-time speed; the final fold produces the immutable
    * `Array[Int]` seed. */
  private type Acc = Array[mutable.LongMap[Int]]

  private val SeedLen = 64 * 64

  private def emptyAcc: Acc = Array.fill(SeedLen)(mutable.LongMap.empty)

  /** Walk one game's move list. `prevEncoded` carries the previous
    * ply's encoded MoveInt so we can pair it with the current move
    * as the (prev → reply) tuple the CMH consumes. */
  private def accumulateGame(game: PgnParser.PgnGame, acc: Acc): Acc =
    var prevEncoded = -1
    game.history.foreach { case (move, _) =>
      val encoded = MoveInt.encodeMove(move)
      if prevEncoded >= 0 then
        val key = MoveInt.fromIdx(prevEncoded) * 64 + MoveInt.toIdx(prevEncoded)
        val tally = acc(key)
        val cur = tally.getOrElse(encoded.toLong, 0)
        tally.update(encoded.toLong, cur + 1)
      prevEncoded = encoded
    }
    acc

  /** Reduce each `(from, to)`-keyed tally to the single modal
    * reply. Ties broken by smallest encoded `MoveInt` so the seed
    * is deterministic across rebuilds. */
  private def foldModal(acc: Acc): Array[Int] =
    val out = Array.fill(SeedLen)(NoReply)
    var i = 0
    while i < SeedLen do
      val tally = acc(i)
      if tally.nonEmpty then
        var bestMove  = NoReply
        var bestCount = -1
        tally.foreach { case (moveKey, count) =>
          val move = moveKey.toInt
          // Modal pick with deterministic tie-break.
          if count > bestCount || (count == bestCount && move < bestMove) then
            bestMove  = move
            bestCount = count
        }
        out(i) = bestMove
      i += 1
    out

  private def writeSeed(seed: Array[Int], path: Path): Task[Unit] =
    ZIO.attemptBlocking {
      Files.createDirectories(path.getParent)
      val bb = ByteBuffer.allocate(seed.length * 4)
      var i = 0
      while i < seed.length do
        bb.putInt(seed(i))
        i += 1
      Files.write(path, bb.array())
    }
