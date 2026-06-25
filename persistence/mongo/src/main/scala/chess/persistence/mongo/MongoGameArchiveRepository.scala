package chess.persistence.mongo

import com.mongodb.client.model.{Filters, ReplaceOptions}
import com.mongodb.reactivestreams.client.MongoDatabase
import org.bson.Document
import zio.*
import zio.json.*

import chess.model.{GameArchive, GameError, GameId}
import chess.persistence.GameArchiveJson.given
import chess.persistence.GameArchiveRepository

/** MongoDB-backed `GameArchiveRepository`. Each finished game is one document in
  * `game_archives`: `{ _id: gameId, json }` (the archive as a JSON blob).
  */
final class MongoGameArchiveRepository(db: MongoDatabase)
    extends GameArchiveRepository:

  private val collection = db.getCollection("game_archives")

  def save(archive: GameArchive): IO[GameError, Unit] =
    val doc = Document("_id", archive.gameId).append("json", archive.toJson)
    MongoOps
      .runDiscard(
        collection.replaceOne(
          Filters.eq("_id", archive.gameId),
          doc,
          ReplaceOptions().upsert(true)
        )
      )
      .mapError(toInfraError)

  def find(gameId: GameId): IO[GameError, Option[GameArchive]] =
    MongoOps
      .headOption(collection.find(Filters.eq("_id", gameId)).limit(1))
      .mapError(toInfraError)
      .flatMap {
        case None => ZIO.succeed(None)
        case Some(doc) =>
          Option(doc.getString("json")) match
            case None       => ZIO.succeed(None)
            case Some(json) => decode(json)
      }

  private def decode(json: String): IO[GameError, Option[GameArchive]] =
    json.fromJson[GameArchive] match
      case Right(archive) => ZIO.succeed(Some(archive))
      case Left(err) =>
        ZIO.fail(GameError.InfrastructureError(s"Archive decode failed: $err"))

  private def toInfraError(t: Throwable): GameError =
    GameError.InfrastructureError(s"Mongo error: ${t.getMessage}")

object MongoGameArchiveRepository:
  val layer: URLayer[MongoDatabase, GameArchiveRepository] =
    ZLayer.fromFunction(MongoGameArchiveRepository(_))
