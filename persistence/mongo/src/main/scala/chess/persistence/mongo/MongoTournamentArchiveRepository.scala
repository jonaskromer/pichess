package chess.persistence.mongo

import com.mongodb.client.model.{Filters, ReplaceOptions}
import com.mongodb.reactivestreams.client.MongoDatabase
import org.bson.Document
import zio.*
import zio.json.*

import chess.model.{GameError, TournamentArchive}
import chess.persistence.TournamentArchiveJson.given
import chess.persistence.TournamentArchiveRepository

/** MongoDB-backed `TournamentArchiveRepository`. Each finished tournament is one
  * document in `tournament_archives`: `{ _id: tournamentId, json }`. */
final class MongoTournamentArchiveRepository(db: MongoDatabase)
    extends TournamentArchiveRepository:

  private val collection = db.getCollection("tournament_archives")

  def save(archive: TournamentArchive): IO[GameError, Unit] =
    val doc = Document("_id", archive.tournamentId).append("json", archive.toJson)
    MongoOps
      .runDiscard(
        collection.replaceOne(
          Filters.eq("_id", archive.tournamentId),
          doc,
          ReplaceOptions().upsert(true)
        )
      )
      .mapError(toInfraError)

  def find(tournamentId: String): IO[GameError, Option[TournamentArchive]] =
    MongoOps
      .headOption(collection.find(Filters.eq("_id", tournamentId)).limit(1))
      .mapError(toInfraError)
      .flatMap {
        case None      => ZIO.succeed(None)
        case Some(doc) => decode(doc).map(Some(_))
      }

  def list: IO[GameError, List[TournamentArchive]] =
    MongoOps
      .toList(collection.find())
      .mapError(toInfraError)
      .flatMap(docs => ZIO.foreach(docs)(decode))

  private def decode(doc: Document): IO[GameError, TournamentArchive] =
    Option(doc.getString("json")) match
      case None =>
        ZIO.fail(GameError.InfrastructureError("Tournament archive: no json"))
      case Some(json) =>
        json.fromJson[TournamentArchive] match
          case Right(a) => ZIO.succeed(a)
          case Left(err) =>
            ZIO.fail(
              GameError.InfrastructureError(s"Tournament decode failed: $err")
            )

  private def toInfraError(t: Throwable): GameError =
    GameError.InfrastructureError(s"Mongo error: ${t.getMessage}")

object MongoTournamentArchiveRepository:
  val layer: URLayer[MongoDatabase, TournamentArchiveRepository] =
    ZLayer.fromFunction(MongoTournamentArchiveRepository(_))
