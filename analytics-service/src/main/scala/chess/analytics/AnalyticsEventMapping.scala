package chess.analytics

import chess.events.GameDomainEvent

/** Pure mapping from a [[GameDomainEvent]] to the (event_type, san) pair
  * stored in the `move_events` table. Extracted from the projection so it
  * can be unit-tested without a ClickHouse instance.
  */
object AnalyticsEventMapping:

  def eventTypeAndSan(event: GameDomainEvent): (String, String) =
    event match
      case e: GameDomainEvent.MoveMade    => "MoveMade"    -> e.san
      case _: GameDomainEvent.GameStarted => "GameStarted" -> ""
      case _: GameDomainEvent.GameLoaded  => "GameLoaded"  -> ""
      case _: GameDomainEvent.Undone      => "Undone"      -> ""
      case _: GameDomainEvent.Redone      => "Redone"      -> ""
      case _: GameDomainEvent.DrawClaimed => "DrawClaimed" -> ""
      case _: GameDomainEvent.Forfeited   => "Forfeited"   -> ""
      case _: GameDomainEvent.GameEnded   => "GameEnded"   -> ""
