package chess.persistence.mongo

import chess.model.{
  InviteCode,
  Lobby,
  LobbyError,
  LobbyId,
  LobbyStatus,
  LobbyVisibility
}
import chess.persistence.LobbyRepository
import com.mongodb.client.model.{Filters, IndexOptions, Indexes, ReplaceOptions, Sorts}
import com.mongodb.reactivestreams.client.MongoDatabase
import org.bson.Document
import zio.*

import java.time.Instant

/** MongoDB-backed `LobbyRepository`. A unique index on `inviteCode` makes
  * the join-by-code lookup a single-document fetch.
  */
final class MongoLobbyRepository(db: MongoDatabase) extends LobbyRepository:

  private val collection = db.getCollection("lobbies")

  def create(lobby: Lobby): IO[LobbyError, Unit] =
    MongoOps
      .runDiscard(collection.insertOne(toDoc(lobby)))
      .mapError(toInfraError)

  def findById(id: LobbyId): IO[LobbyError, Option[Lobby]] =
    MongoOps
      .headOption(collection.find(Filters.eq("_id", id)).limit(1))
      .mapError(toInfraError)
      .map(_.flatMap(fromDoc))

  def findByInviteCode(code: InviteCode): IO[LobbyError, Option[Lobby]] =
    MongoOps
      .headOption(
        collection.find(Filters.eq("inviteCode", code.value)).limit(1)
      )
      .mapError(toInfraError)
      .map(_.flatMap(fromDoc))

  def update(lobby: Lobby): IO[LobbyError, Unit] =
    val filter = Filters.eq("_id", lobby.id)
    MongoOps
      .runDiscard(
        collection
          .replaceOne(filter, toDoc(lobby), ReplaceOptions().upsert(true))
      )
      .mapError(toInfraError)

  def delete(id: LobbyId): IO[LobbyError, Unit] =
    MongoOps
      .runDiscard(collection.deleteOne(Filters.eq("_id", id)))
      .mapError(toInfraError)

  def listPublicWaiting(): IO[LobbyError, List[Lobby]] =
    MongoOps
      .toList(
        collection
          .find(
            Filters.and(
              Filters.eq("visibility", LobbyVisibility.Public.toString),
              Filters.eq("status", LobbyStatus.Waiting.toString)
            )
          )
          .sort(Sorts.ascending("createdAt"))
      )
      .mapError(toInfraError)
      .map(_.flatMap(fromDoc))

  private def toDoc(lobby: Lobby): Document =
    val doc = Document("_id", lobby.id)
      .append("inviteCode", lobby.inviteCode.value)
      .append("hostNickname", lobby.hostNickname)
      .append("hostSessionId", lobby.hostSessionId)
      .append("guestNickname", lobby.guestNickname.orNull)
      .append("guestSessionId", lobby.guestSessionId.orNull)
      .append("visibility", lobby.visibility.toString)
      .append("allowUndo", lobby.allowUndo: java.lang.Boolean)
      .append("allowSpectate", lobby.allowSpectate: java.lang.Boolean)
      .append("spectatorLimit", lobby.spectatorLimit: java.lang.Integer)
      .append("status", lobby.status.toString)
      .append("gameId", lobby.gameId.orNull)
      .append("createdAt", lobby.createdAt: java.lang.Long)
      .append("updatedAt", Instant.now.toString)
    doc

  private def fromDoc(doc: Document): Option[Lobby] =
    for
      id          <- Option(doc.getString("_id"))
      rawCode     <- Option(doc.getString("inviteCode"))
      host        <- Option(doc.getString("hostNickname"))
      hostSession <- Option(doc.getString("hostSessionId"))
      visibility  <- Option(doc.getString("visibility"))
                       .flatMap(s =>
                         LobbyVisibility.values.find(_.toString == s)
                       )
      status      <- Option(doc.getString("status"))
                       .flatMap(s => LobbyStatus.values.find(_.toString == s))
      created     <- Option(doc.getLong("createdAt")).map(_.longValue)
    yield Lobby(
      id = id,
      inviteCode = InviteCode.unsafe(rawCode),
      hostNickname = host,
      hostSessionId = hostSession,
      guestNickname = Option(doc.getString("guestNickname")),
      guestSessionId = Option(doc.getString("guestSessionId")),
      visibility = visibility,
      allowUndo = Option(doc.getBoolean("allowUndo")).fold(false)(_.booleanValue),
      allowSpectate =
        Option(doc.getBoolean("allowSpectate")).fold(false)(_.booleanValue),
      spectatorLimit =
        Option(doc.getInteger("spectatorLimit")).fold(0)(_.intValue),
      status = status,
      createdAt = created,
      gameId = Option(doc.getString("gameId"))
    )

  private def toInfraError(t: Throwable): LobbyError =
    LobbyError.InfrastructureError(s"Mongo error: ${t.getMessage}")

object MongoLobbyRepository:

  /** Create the unique invite-code index + a public-waiting compound index
    * if they don't already exist. Run once at service startup.
    */
  def ensureIndexes(db: MongoDatabase): Task[Unit] =
    val coll = db.getCollection("lobbies")
    MongoOps.runDiscard(
      coll.createIndex(
        Indexes.ascending("inviteCode"),
        IndexOptions().unique(true)
      )
    ) *>
      MongoOps.runDiscard(
        coll.createIndex(
          Indexes.ascending("visibility", "status", "createdAt")
        )
      )

  val layer: URLayer[MongoDatabase, LobbyRepository] =
    ZLayer.fromFunction(MongoLobbyRepository(_))

  /** Convenience: prepares the database (ensures indexes) and produces the
    * repo layer. Use this from service Mains.
    */
  val withIndexesLayer: ZLayer[MongoDatabase, Throwable, LobbyRepository] =
    ZLayer.fromZIO(
      for
        db <- ZIO.service[MongoDatabase]
        _  <- ensureIndexes(db)
      yield MongoLobbyRepository(db)
    )
