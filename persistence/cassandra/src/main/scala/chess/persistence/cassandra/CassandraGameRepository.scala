package chess.persistence.cassandra

import java.time.Instant

import com.datastax.oss.driver.api.core.CqlSession
import zio.*

import chess.codec.{FenParserRegex, FenSerializer}
import chess.model.board.GameState
import chess.model.{GameError, GameId}
import chess.persistence.GameRepository

/** Cassandra-backed `GameRepository`. Single table partitioned by gameId —
  * the textbook KV use of a wide-column store. State stored as FEN, written
  * via prepared statements and bound parameters so query strings are cached
  * server-side.
  */
final class CassandraGameRepository(session: CqlSession) extends GameRepository:

  private val saveStmt =
    session.prepare(
      "INSERT INTO games (game_id, fen, updated_at) VALUES (?, ?, ?)"
    )
  private val loadStmt =
    session.prepare("SELECT fen FROM games WHERE game_id = ?")
  private val deleteStmt =
    session.prepare("DELETE FROM games WHERE game_id = ?")

  def save(id: GameId, state: GameState): IO[GameError, Unit] =
    val bound = saveStmt.bind(id, FenSerializer.serialize(state), Instant.now)
    ZIO.fromCompletionStage(session.executeAsync(bound)).unit
      .mapError(toInfraError)

  def load(id: GameId): IO[GameError, Option[GameState]] =
    ZIO
      .fromCompletionStage(session.executeAsync(loadStmt.bind(id)))
      .mapError(toInfraError)
      .flatMap { rs =>
        Option(rs.one()).map(_.getString("fen")) match
          case None      => ZIO.succeed(None)
          case Some(fen) => FenParserRegex.parse(fen).map(Some(_))
      }

  def delete(id: GameId): IO[GameError, Unit] =
    ZIO.fromCompletionStage(session.executeAsync(deleteStmt.bind(id))).unit
      .mapError(toInfraError)

  private def toInfraError(t: Throwable): GameError =
    GameError.InfrastructureError(s"Cassandra error: ${t.getMessage}")

object CassandraGameRepository:
  val layer: URLayer[CqlSession, GameRepository] =
    ZLayer.fromFunction(CassandraGameRepository(_))
