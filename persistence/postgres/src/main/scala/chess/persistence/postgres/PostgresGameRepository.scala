package chess.persistence.postgres

import chess.codec.{FenParserRegex, FenSerializer}
import chess.model.{GameError, GameId}
import chess.model.board.GameState
import chess.persistence.GameRepository
import slick.jdbc.PostgresProfile.api.*
import zio.*

import java.time.Instant

/** Slick-backed `GameRepository`. State is serialised as FEN — the canonical
  * format already produced/consumed by the codec module — and stored in a
  * single TEXT column. The relational schema buys us a unique-id constraint
  * and `updated_at` for free.
  */
final class PostgresGameRepository(db: PostgresDatabase) extends GameRepository:

  def save(id: GameId, state: GameState): IO[GameError, Unit] =
    val fen = FenSerializer.serialize(state)
    // Slick's `Tables.games.insertOrUpdate` compiles to a two-statement
    // SELECT-then-INSERT-or-UPDATE for tables without a server-generated
    // identity column, which is NOT atomic under concurrent writes against
    // the same primary key — two parallel saves both observe "row absent",
    // both INSERT, and the loser surfaces as `duplicate key value violates
    // unique constraint "games_pkey"`. Drop to raw `INSERT … ON CONFLICT
    // DO UPDATE` so the upsert is a single atomic statement at the
    // Postgres level, satisfying the contract test for concurrent saves.
    //
    // `sqlu` needs a `SetParameter[T]` for every interpolated value;
    // Slick ships one for `java.sql.Timestamp` but not for
    // `java.time.Instant`, so convert at the call site.
    val nowTs = java.sql.Timestamp.from(Instant.now)
    val upsert =
      sqlu"""
        INSERT INTO games (id, fen, updated_at)
        VALUES (${id}, ${fen}, ${nowTs})
        ON CONFLICT (id) DO UPDATE
          SET fen = EXCLUDED.fen,
              updated_at = EXCLUDED.updated_at
      """
    db.run(upsert).unit
      .mapError(toInfraError)

  def load(id: GameId): IO[GameError, Option[GameState]] =
    val q = Tables.games.filter(_.id === id).map(_.fen).result.headOption
    db.run(q)
      .mapError(toInfraError)
      .flatMap {
        case Some(fen) => FenParserRegex.parse(fen).map(Some(_))
        case None      => ZIO.succeed(None)
      }

  def delete(id: GameId): IO[GameError, Unit] =
    val q = Tables.games.filter(_.id === id).delete
    db.run(q).unit
      .mapError(toInfraError)

  private def toInfraError(t: Throwable): GameError =
    GameError.InfrastructureError(s"Postgres error: ${t.getMessage}")

object PostgresGameRepository:

  /** Build the layer from an existing `PostgresDatabase`. Schema creation is
    * NOT performed here — call [[ensureSchema]] once at service startup.
    */
  val layer: URLayer[PostgresDatabase, GameRepository] =
    ZLayer.fromFunction(PostgresGameRepository(_))
