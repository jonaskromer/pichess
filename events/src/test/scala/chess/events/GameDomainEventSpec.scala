package chess.events

import zio.json.*
import zio.test.*

object GameDomainEventSpec extends ZIOSpecDefault:

  private val initialFen =
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  private val afterE4Fen =
    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"

  private def roundTrip(e: GameDomainEvent): TestResult =
    val json    = e.toJson
    val decoded = json.fromJson[GameDomainEvent]
    assertTrue(decoded == Right(e))

  override def spec: Spec[Any, Any] = suite("GameDomainEvent")(
    test("GameStarted round-trips") {
      roundTrip(GameDomainEvent.GameStarted("g1", initialFen, 1L))
    },
    test("GameLoaded round-trips with history") {
      roundTrip(
        GameDomainEvent.GameLoaded("g2", afterE4Fen, initialFen, 1, 2L)
      )
    },
    test("MoveMade round-trips") {
      roundTrip(
        GameDomainEvent.MoveMade("g3", afterE4Fen, "e2-e4", "e4", 3L)
      )
    },
    test("Undone round-trips") {
      roundTrip(GameDomainEvent.Undone("g4", initialFen, 4L))
    },
    test("Redone round-trips") {
      roundTrip(GameDomainEvent.Redone("g5", afterE4Fen, 5L))
    },
    test("DrawClaimed round-trips") {
      roundTrip(
        GameDomainEvent.DrawClaimed("g6", afterE4Fen, "ThreefoldRepetition", 6L)
      )
    },
    test("Forfeited round-trips") {
      roundTrip(
        GameDomainEvent.Forfeited("g7", afterE4Fen, "White", 7L)
      )
    },
    test("GameEnded round-trips") {
      roundTrip(
        GameDomainEvent.GameEnded("g8", afterE4Fen, "Checkmate(White)", 8L)
      )
    },
    test("discriminator field is `type`") {
      val e: GameDomainEvent =
        GameDomainEvent.MoveMade("g9", afterE4Fen, "e2-e4", "e4", 9L)
      val json = e.toJson
      assertTrue(
        json.contains("\"type\":\"MoveMade\""),
        json.contains("\"gameId\":\"g9\""),
        json.contains("\"san\":\"e4\"")
      )
    },
    test("decoder rejects unknown event type") {
      val json = """{"type":"NoSuchEvent","gameId":"x","resultingFen":"","occurredAt":0}"""
      assertTrue(json.fromJson[GameDomainEvent].isLeft)
    },
    test("topic name is the canonical chess.game-events") {
      assertTrue(Topics.GameEvents == "chess.game-events")
    }
  )
