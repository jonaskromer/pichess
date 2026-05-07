package chess.persistence.mongo

import chess.model.{InviteCode, Lobby, LobbyError, LobbyId, LobbyStatus}
import chess.persistence.LobbyRepository
import com.mongodb.client.model.{Filters, IndexOptions, Indexes, ReplaceOptions}
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

  private def toDoc(lobby: Lobby): Document =
    val doc = Document("_id", lobby.id)
      .append("inviteCode", lobby.inviteCode.value)
      .append("hostNickname", lobby.hostNickname)
      .append("guestNickname", lobby.guestNickname.orNull)
      .append("status", lobby.status.toString)
      .append("gameId", lobby.gameId.orNull)
      .append("createdAt", lobby.createdAt: java.lang.Long)
      .append("updatedAt", Instant.now.toString)
    doc

  private def fromDoc(doc: Document): Option[Lobby] =
    for
      id      <- Option(doc.getString("_id"))
      rawCode <- Option(doc.getString("inviteCode"))
      host    <- Option(doc.getString("hostNickname"))
      status  <- Option(doc.getString("status"))
                   .flatMap(s =>
                     LobbyStatus.values.find(_.toString == s)
                   )
      created <- Option(doc.getLong("createdAt")).map(_.longValue)
    yield Lobby(
      id = id,
      inviteCode = InviteCode.unsafe(rawCode),
      hostNickname = host,
      guestNickname = Option(doc.getString("guestNickname")),
      status = status,
      createdAt = created,
      gameId = Option(doc.getString("gameId"))
    )

  private def toInfraError(t: Throwable): LobbyError =
    LobbyError.InfrastructureError(s"Mongo error: ${t.getMessage}")

object MongoLobbyRepository:

  /** Create the unique invite-code index if it doesn't already exist. Run
    * once at service startup before serving traffic.
    */
  def ensureIndexes(db: MongoDatabase): Task[Unit] =
    MongoOps
      .runDiscard(
        db.getCollection("lobbies")
          .createIndex(
            Indexes.ascending("inviteCode"),
            IndexOptions().unique(true)
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
