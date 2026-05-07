package chess.persistence.mongo

import com.mongodb.ConnectionString
import com.mongodb.reactivestreams.client.{
  MongoClient,
  MongoClients,
  MongoDatabase
}
import zio.*

/** Connection-pooled MongoDB client wired from `PICHESS_MONGO_URL` /
  * `PICHESS_MONGO_DB`. Lifecycle is scoped — the underlying client is closed
  * when the surrounding ZIO scope tears down.
  */
object MongoClientLayer:

  val EnvUrl: String = "PICHESS_MONGO_URL"
  val EnvDb: String = "PICHESS_MONGO_DB"

  final case class Settings(url: String, database: String)

  def settingsFromEnv: Task[Settings] =
    for
      url <- zio.System.env(EnvUrl).flatMap {
               case Some(u) if u.trim.nonEmpty => ZIO.succeed(u)
               case _ =>
                 ZIO.fail(
                   IllegalStateException(
                     s"$EnvUrl must be set when PICHESS_BACKEND=mongo"
                   )
                 )
             }
      db  <- zio.System.env(EnvDb).map(_.getOrElse("pichess"))
    yield Settings(url, db)

  /** Build a scoped `MongoDatabase` from explicit settings. */
  def make(settings: Settings): ZIO[Scope, Throwable, MongoDatabase] =
    ZIO
      .acquireRelease(
        ZIO.attempt(MongoClients.create(ConnectionString(settings.url)))
      )(client => ZIO.attempt(client.close()).orDie)
      .map(_.getDatabase(settings.database))

  /** Env-driven default layer used by the service Mains. */
  val layer: ZLayer[Any, Throwable, MongoDatabase] =
    ZLayer.scoped(settingsFromEnv.flatMap(make))
