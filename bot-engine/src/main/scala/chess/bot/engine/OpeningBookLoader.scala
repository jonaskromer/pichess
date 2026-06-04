package chess.bot.engine

import scala.io.Source
import scala.util.Using

import zio.*

import chess.codec.PgnParser
import chess.model.board.{GameState, Move}
import chess.model.rules.Zobrist

/** Builds an [[OpeningBook]] from a PGN file on the classpath.
  *
  * The committed `openings/main-lines.pgn` resource holds a curated
  * set of common openings played ~12 plies deep. At startup we parse
  * each game, walk its move history, and add every (pre-state,
  * move) pair to the in-memory book — so any position reachable via
  * a known main line gets a book reply.
  *
  * When multiple games visit the same position with different next
  * moves (e.g. Italian Game and Italian Pianissimo both reach
  * 3.Bc4 Bc5), the LATER one in the file wins. This is intentional
  * and predictable: we don't track aggregate statistics here (no
  * weighting by frequency or Elo); the curator's ordering choice in
  * the PGN file IS the policy.
  *
  * For data-driven books backed by millions of games + statistical
  * weighting, see `chess.bot.data.DuckDbOpeningBook` — the PGN
  * loader here is the portable / committable variant.
  */
object OpeningBookLoader:

  sealed trait LoadError extends RuntimeException
  final case class MissingResource(path: String)
      extends RuntimeException(s"opening book resource not found: $path") with LoadError
  final case class MalformedPgn(path: String, reason: String)
      extends RuntimeException(s"opening book PGN failed to parse in $path: $reason") with LoadError

  /** Default classpath location of the committed main-lines book. */
  val DefaultResource: String = "openings/main-lines.pgn"

  /** Load the default committed book. */
  def loadDefault(maxPly: Int = 24): IO[LoadError, OpeningBook] =
    loadResource(DefaultResource, maxPly)

  /** Load from any classpath PGN resource. */
  def loadResource(path: String, maxPly: Int = 24): IO[LoadError, OpeningBook] =
    for
      body  <- readResource(path)
      entries <- buildEntries(path, body)
    yield OpeningBook.inMemory(entries, maxPly)

  /** Read the resource body as a UTF-8 string. */
  private def readResource(path: String): IO[LoadError, String] =
    ZIO
      .attemptBlocking {
        val stream = Option(getClass.getClassLoader.getResourceAsStream(path))
          .getOrElse(throw MissingResource(path))
        Using.resource(Source.fromInputStream(stream, "UTF-8"))(_.mkString)
      }
      .refineToOrDie[LoadError]

  /** Split the multi-game PGN into individual games, parse each, and
    * fold the move history into a `Map[zobrist, Move]`.
    *
    * Bad games are tolerated (logged + skipped). This is the
    * committable book — typo'd PGN in the source file shouldn't
    * block the bot from starting up; the rest of the entries are
    * still useful.
    */
  private def buildEntries(
      path: String,
      pgn: String,
  ): UIO[Map[Long, Move]] =
    val games = splitGames(pgn)
    ZIO.foldLeft(games)(Map.empty[Long, Move]) { (acc, gameText) =>
      PgnParser.parse(gameText).foldZIO(
        err =>
          ZIO.logWarning(s"$path: skipping unparseable game: ${err.message}").as(acc),
        parsed => ZIO.succeed(acc ++ historyToEntries(parsed)),
      )
    }

  /** Walk one game's (move, post-state) history, emitting one
    * `pre-state-zobrist → move` mapping per ply. The pre-state is
    * tracked manually because PgnParser's history only carries the
    * post-state. */
  private[engine] def historyToEntries(
      game: PgnParser.PgnGame,
  ): Map[Long, Move] =
    val builder = Map.newBuilder[Long, Move]
    var pre     = game.initialState
    game.history.foreach { case (move, post) =>
      builder += Zobrist.hash(pre) -> move
      pre = post
    }
    builder.result()

  /** Same algorithm as bot-train's PgnIngest.splitGames — kept local
    * so bot-engine doesn't gain a dependency on bot-train. */
  private[engine] def splitGames(pgn: String): List[String] =
    val tokens = pgn.split("(?m)^\\[Event ").toList
    tokens.tail.map(t => "[Event " + t).filter(_.trim.nonEmpty)
