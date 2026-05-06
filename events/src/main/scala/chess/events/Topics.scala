package chess.events

object Topics:

  /** Single topic for all game domain events.
    *
    * Partition key is `gameId` — guarantees per-game ordering across event
    * types (consumers need NewGame → MoveMade → … → GameEnded ordering). One
    * topic over per-event-type splits keeps the merge logic out of consumers.
    */
  val GameEvents: String = "chess.game-events"
