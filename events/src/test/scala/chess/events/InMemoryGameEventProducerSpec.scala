package chess.events

import chess.model.GameError
import zio.*
import zio.test.*

object InMemoryGameEventProducerSpec extends ZIOSpecDefault:

  override def spec: Spec[Any, Any] = suite("InMemoryGameEventProducer")(
    test("records published events in order") {
      val e1: GameDomainEvent = GameDomainEvent.GameStarted("g1", "fen0", 1L)
      val e2: GameDomainEvent =
        GameDomainEvent.MoveMade("g1", "fen1", "e2-e4", "e4", 2L)
      for
        producer <- InMemoryGameEventProducer.make
        _        <- producer.publish(e1)
        _        <- producer.publish(e2)
        events   <- producer.recorded
      yield assertTrue(events == Vector(e1, e2))
    },
    test("recorded is empty before any publish") {
      for
        producer <- InMemoryGameEventProducer.make
        events   <- producer.recorded
      yield assertTrue(events.isEmpty)
    },
    test("layer provides both trait and concrete impl") {
      val program: ZIO[GameEventProducer & InMemoryGameEventProducer, GameError, Vector[
        GameDomainEvent
      ]] =
        for
          _        <- GameEventProducer.publish(
                        GameDomainEvent.GameStarted("g2", "fen", 0L)
                      )
          inMemory <- ZIO.service[InMemoryGameEventProducer]
          events   <- inMemory.recorded
        yield events

      program.provide(InMemoryGameEventProducer.layer).map { events =>
        assertTrue(events.size == 1, events.head.gameId == "g2")
      }
    }
  )
