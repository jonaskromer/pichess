package chess.events

import chess.model.GameError
import zio.*

/** Test/dev producer that records published events in a `Ref` instead of
  * writing to Kafka. Used:
  *   - in unit tests, where it lets specs assert which events were published
  *   - by `app/Main` when `KAFKA_BOOTSTRAP_SERVERS` is unset, so `sbt run`
  *     boots without needing a broker
  */
final class InMemoryGameEventProducer private (
    ref: Ref[Vector[GameDomainEvent]]
) extends GameEventProducer:

  def publish(event: GameDomainEvent): IO[GameError, Unit] =
    ref.update(_ :+ event)

  /** All events published so far, in publish order. */
  val recorded: UIO[Vector[GameDomainEvent]] = ref.get

object InMemoryGameEventProducer:

  val make: UIO[InMemoryGameEventProducer] =
    Ref.make(Vector.empty[GameDomainEvent]).map(new InMemoryGameEventProducer(_))

  /** Provides both the trait (for service users) and the concrete class (for
    * test inspection of `recorded`). Use in test/dev wiring.
    */
  val layer: ULayer[GameEventProducer & InMemoryGameEventProducer] =
    ZLayer.fromZIO(make) >+>
      ZLayer.fromFunction((p: InMemoryGameEventProducer) => p: GameEventProducer)
