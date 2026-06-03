package chess.persistence.mongo

import java.time.Instant

import com.mongodb.client.model.{Filters, ReplaceOptions}
import com.mongodb.reactivestreams.client.MongoDatabase
import org.bson.Document
import zio.*

import chess.codec.{FenParserRegex, FenSerializer}
import chess.model.board.GameState
import chess.model.{GameError, GameId}
import chess.persistence.GameRepository

/** MongoDB-backed `GameRepository`. Each game is one document in the
  * `games` collection: `{ _id: gameId, fen, updatedAt }`. Schema-less,
  * trivial — the document store demo's primary virtue is that there's no
  * schema to maintain.
  */
final class MongoGameRepository(db: MongoDatabase) extends GameRepository:

  private val collection = db.getCollection("games")

  def save(id: GameId, state: GameState): IO[GameError, Unit] =
    val doc = Document("_id", id)
      .append("fen", FenSerializer.serialize(state))
      .append("updatedAt", Instant.now.toString)
    val filter = Filters.eq("_id", id)
    MongoOps
      .runDiscard(
        collection.replaceOne(filter, doc, ReplaceOptions().upsert(true))
      )
      .mapError(toInfraError)

  def load(id: GameId): IO[GameError, Option[GameState]] =
    MongoOps
      .headOption(collection.find(Filters.eq("_id", id)).limit(1))
      .mapError(toInfraError)
      .flatMap {
        case None => ZIO.succeed(None)
        case Some(doc) =>
          Option(doc.getString("fen")) match
            case None => ZIO.succeed(None)
            case Some(fen) => FenParserRegex.parse(fen).map(Some(_))
      }

  def delete(id: GameId): IO[GameError, Unit] =
    MongoOps
      .runDiscard(collection.deleteOne(Filters.eq("_id", id)))
      .mapError(toInfraError)

  private def toInfraError(t: Throwable): GameError =
    GameError.InfrastructureError(s"Mongo error: ${t.getMessage}")

object MongoGameRepository:
  val layer: URLayer[MongoDatabase, GameRepository] =
    ZLayer.fromFunction(MongoGameRepository(_))
