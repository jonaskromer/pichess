package chess.analytics

import chess.events.GameDomainEvent
import zio.test.*

object AnalyticsEventMappingSpec extends ZIOSpecDefault:

  private val gid = "g1"
  private val fen = "fen"

  def spec = suite("AnalyticsEventMapping.eventTypeAndSan")(
    test("MoveMade carries the SAN through to the wire format") {
      val (k, s) =
        AnalyticsEventMapping.eventTypeAndSan(
          GameDomainEvent.MoveMade(gid, fen, "e2-e4", "e4", 0L)
        )
      assertTrue(k == "MoveMade", s == "e4")
    },
    test("GameStarted maps to the GameStarted discriminator with empty SAN") {
      val (k, s) =
        AnalyticsEventMapping.eventTypeAndSan(
          GameDomainEvent.GameStarted(gid, fen, 0L)
        )
      assertTrue(k == "GameStarted", s.isEmpty)
    },
    test("GameLoaded -> GameLoaded discriminator") {
      val (k, s) =
        AnalyticsEventMapping.eventTypeAndSan(
          GameDomainEvent.GameLoaded(gid, fen, fen, 0, 0L)
        )
      assertTrue(k == "GameLoaded", s.isEmpty)
    },
    test("Undone -> Undone discriminator") {
      val (k, _) =
        AnalyticsEventMapping.eventTypeAndSan(
          GameDomainEvent.Undone(gid, fen, 0L)
        )
      assertTrue(k == "Undone")
    },
    test("Redone -> Redone discriminator") {
      val (k, _) =
        AnalyticsEventMapping.eventTypeAndSan(
          GameDomainEvent.Redone(gid, fen, 0L)
        )
      assertTrue(k == "Redone")
    },
    test("DrawClaimed -> DrawClaimed discriminator") {
      val (k, _) =
        AnalyticsEventMapping.eventTypeAndSan(
          GameDomainEvent.DrawClaimed(gid, fen, "FiftyMoveRule", 0L)
        )
      assertTrue(k == "DrawClaimed")
    },
    test("Forfeited -> Forfeited discriminator") {
      val (k, _) =
        AnalyticsEventMapping.eventTypeAndSan(
          GameDomainEvent.Forfeited(gid, fen, "Black", 0L)
        )
      assertTrue(k == "Forfeited")
    },
    test("GameEnded -> GameEnded discriminator") {
      val (k, _) =
        AnalyticsEventMapping.eventTypeAndSan(
          GameDomainEvent.GameEnded(gid, fen, "Checkmate(White)", 0L)
        )
      assertTrue(k == "GameEnded")
    }
  )
