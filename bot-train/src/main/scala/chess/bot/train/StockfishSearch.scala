package chess.bot.train

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.util.concurrent.locks.ReentrantLock

import zio.*

import chess.bot.engine.Search
import chess.codec.{FenSerializer, UciCodec}
import chess.model.board.{GameState, Move}

/** Implements [[Search]] by talking UCI to an external chess
  * engine subprocess (Stockfish in particular, but any UCI-
  * compliant engine works).
  *
  * Lifecycle:
  *   - [[spawn]] forks the process, handshakes "uci" + "isready",
  *     and applies any UCI options (skill level, threads, hash).
  *   - Each call to [[bestMove]] writes a "position fen …" + "go
  *     depth N", reads back the "bestmove …" line, and parses
  *     the move via [[UciCodec]].
  *   - The subprocess is released on Scope close (sends "quit"
  *     and joins).
  *
  * Concurrency: the underlying subprocess is single-threaded
  * (one stdin / stdout pair), so calls are serialised through a
  * `ReentrantLock`. Use one [[StockfishSearch]] per game; if two
  * matches are running concurrently each should have its own
  * instance.
  *
  * Use case: a canonical opponent for [[Tournament]] —
  * measures our bot's strength against an external, well-known
  * reference engine instead of just against itself. */
final class StockfishSearch private (
    process: Process,
    in: BufferedWriter,
    out: BufferedReader,
    lock: ReentrantLock,
    label: String,
) extends Search:

  def bestMove(
      state: GameState,
      depth: Int,
      history: Set[Long],
  ): UIO[Option[Move]] =
    ZIO.attemptBlocking {
      lock.lock()
      try
        send(s"position fen ${FenSerializer.serialize(state)}")
        send(s"go depth $depth")
        readBestMove()
      finally lock.unlock()
    }.orDie

  /** Send a single line of UCI input to the subprocess. */
  private def send(line: String): Unit =
    in.write(line)
    in.newLine()
    in.flush()

  /** Read until we see a `bestmove …` line; return the parsed
    * [[Move]] or `None` if the engine reported `0000` (its UCI
    * convention for "no legal move / mate / stalemate"). Returns
    * `None` on EOF (engine died) for robustness. */
  private def readBestMove(): Option[Move] =
    var line = out.readLine()
    while line != null && !line.startsWith("bestmove ") do
      line = out.readLine()
    if line == null then None
    else
      val parts = line.split(" +")
      val uci = if parts.length >= 2 then parts(1) else ""
      if uci == "0000" || uci.isEmpty then None
      else UciCodec.parse(uci).toOption

  override def toString: String = s"StockfishSearch($label)"

  /** Send `quit` + wait briefly for the subprocess to exit, then
    * force-kill if still alive (e.g. on a hung engine). Visible
    * inside the file so the companion's resource-release lambda
    * can call it without poking at private fields. */
  private[train] def shutdown(): Unit =
    try
      send("quit")
      process.waitFor()
    catch
      case _: Throwable => ()
    finally
      if process.isAlive then process.destroyForcibly()
      in.close()
      out.close()

object StockfishSearch:

  /** Spawn a UCI engine subprocess and apply the given options.
    * Released on Scope close via `quit` + `waitFor`.
    *
    * @param binary       command name or path. Defaults to
    *                     `stockfish` on `PATH`; override via the
    *                     `STOCKFISH_BIN` env var if needed.
    * @param skillLevel   Stockfish-specific UCI option, 0–20
    *                     (0 weakest, 20 full strength). `None`
    *                     skips the option entirely (any engine).
    * @param threads      `Threads` UCI option. Default 1 to keep
    *                     measurements deterministic + fair vs
    *                     our single-thread search.
    * @param hashMb       transposition-table size in MB.
    * @param label        free-form tag used in `toString` for
    *                     logging clarity ("stockfish-skill5"). */
  def spawn(
      binary: String = sys.env.getOrElse("STOCKFISH_BIN", "stockfish"),
      skillLevel: Option[Int] = None,
      threads: Int = 1,
      hashMb: Int = 16,
      label: String = "stockfish",
      syzygyPath: Option[String] = None,
      // When > 0, Stockfish probes Syzygy as soon as the position
      // has ≤ this many pieces. Default 5 matches the 3-4-5 TB set
      // we ship; raise to 7 if 6-7-piece tables are mirrored too.
      syzygyProbeLimit: Int = 5,
      // Calibrated strength via `UCI_LimitStrength` + `UCI_Elo`. When
      // set, this is preferred over Skill Level for ABSOLUTE Elo
      // anchoring — SF caps its own play to (roughly) this Elo, so
      // the SF-Elo at which we score 50% estimates our absolute Elo.
      uciElo: Option[Int] = None,
  ): ZIO[Scope, Throwable, StockfishSearch] =
    ZIO.acquireRelease(
      start(binary, skillLevel, threads, hashMb, label, syzygyPath, syzygyProbeLimit, uciElo)
    )(s => ZIO.attemptBlocking(s.shutdown()).orDie)

  /** Internal — spawn + handshake without resource management. */
  private def start(
      binary: String,
      skillLevel: Option[Int],
      threads: Int,
      hashMb: Int,
      label: String,
      syzygyPath: Option[String],
      syzygyProbeLimit: Int,
      uciElo: Option[Int],
  ): ZIO[Any, Throwable, StockfishSearch] =
    ZIO.attemptBlocking {
      val pb = new ProcessBuilder(binary)
      pb.redirectErrorStream(true) // merge stderr into stdout so we can ignore log spam
      val proc = pb.start()
      val in   = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream))
      val out  = new BufferedReader(new InputStreamReader(proc.getInputStream))

      // Handshake: send "uci", drain until "uciok".
      in.write("uci"); in.newLine(); in.flush()
      drainUntil(out, _ == "uciok")

      // Apply options. Each `setoption name X value Y` is silent;
      // wait for the next "readyok" to know they took effect.
      skillLevel.foreach { lvl =>
        in.write(s"setoption name Skill Level value $lvl"); in.newLine()
      }
      uciElo.foreach { elo =>
        in.write("setoption name UCI_LimitStrength value true"); in.newLine()
        in.write(s"setoption name UCI_Elo value $elo"); in.newLine()
      }
      in.write(s"setoption name Threads value $threads"); in.newLine()
      in.write(s"setoption name Hash value $hashMb"); in.newLine()
      // Syzygy tablebase wiring — Stockfish loads `.rtbw`/`.rtbz`
      // files from `SyzygyPath` (semicolon-separated dir list) and
      // probes at `SyzygyProbeLimit`-or-fewer pieces. With these
      // set, SF's `bestmove` for low-piece positions is TB-perfect
      // even when the search depth is shallow.
      syzygyPath.foreach { path =>
        in.write(s"setoption name SyzygyPath value $path"); in.newLine()
        in.write(s"setoption name SyzygyProbeLimit value $syzygyProbeLimit"); in.newLine()
      }
      in.write("isready"); in.newLine(); in.flush()
      drainUntil(out, _ == "readyok")

      // New-game reset so the engine treats each tournament
      // match as a fresh start (UCI engines maintain hash tables
      // across positions otherwise, which could bias deeper
      // searches based on prior game state).
      in.write("ucinewgame"); in.newLine()
      in.write("isready"); in.newLine(); in.flush()
      drainUntil(out, _ == "readyok")

      new StockfishSearch(proc, in, out, new ReentrantLock(), label)
    }

  /** Internal — read lines from the engine until the predicate
    * matches OR EOF. Used to wait for `uciok` / `readyok`. */
  private def drainUntil(out: BufferedReader, p: String => Boolean): Unit =
    var line = out.readLine()
    while line != null && !p(line) do
      line = out.readLine()

