package chess.opening

import zio.*

import chess.events.GameDomainEvent

/** Pure projection logic — translates a single domain event into the
  * appropriate `OpeningTree` mutation, threading per-game "last seen FEN"
  * state through a `Ref`. Kafka glue lives separately in
  * [[KafkaOpeningConsumer]] so the projection can be exercised in unit
  * tests without spinning up a broker.
  */
trait OpeningProjection:
  def applyEvent(event: GameDomainEvent): Task[Unit]

object OpeningProjection:

  /** Build a projection backed by the supplied tree. The per-game tracker
    * lives inside the returned instance.
    */
  def make(tree: OpeningTree): UIO[OpeningProjection] =
    Ref.make(Map.empty[String, String]).map(LiveOpeningProjection(tree, _))

  val layer: URLayer[OpeningTree, OpeningProjection] =
    ZLayer.fromZIO(ZIO.serviceWithZIO[OpeningTree](make))

private final class LiveOpeningProjection(
    tree: OpeningTree,
    tracker: Ref[Map[String, String]]
) extends OpeningProjection:

  def applyEvent(event: GameDomainEvent): Task[Unit] = event match
    case e: GameDomainEvent.GameStarted =>
      seed(e.gameId, e.resultingFen)
    case e: GameDomainEvent.GameLoaded =>
      seed(e.gameId, e.resultingFen)
    case e: GameDomainEvent.MoveMade =>
      for
        before <- tracker.get.map(_.get(e.gameId))
        out <- before match
                 case Some(beforeFen) =>
                   tree.recordMove(beforeFen, e.san, e.resultingFen) *>
                     seed(e.gameId, e.resultingFen)
                 case None =>
                   // No seed for this gameId — projector started mid-game,
                   // or events arrived out of order. Just remember the new
                   // FEN so future moves project against it.
                   seed(e.gameId, e.resultingFen)
      yield out
    case e: (GameDomainEvent.GameEnded | GameDomainEvent.Forfeited |
        GameDomainEvent.DrawClaimed) =>
      tracker.update(_ - e.gameId)
    case _: (GameDomainEvent.Undone | GameDomainEvent.Redone) =>
      ZIO.unit

  private def seed(gameId: String, fen: String): UIO[Unit] =
    tracker.update(_ + (gameId -> fen))
