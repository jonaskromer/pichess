package chess.events

object Topics:

  /** Single topic for all game domain events.
    *
    * Partition key is `gameId` — guarantees per-game ordering across event
    * types (consumers need NewGame → MoveMade → … → GameEnded ordering). One
    * topic over per-event-type splits keeps the merge logic out of consumers.
    */
  val GameEvents: String = "chess.game-events"

  /** Speed-layer analytics output: per-completed-game `GameSummary` JSON,
    * produced by the Spark analytics job and consumed by the gateway (live SSE
    * panel) and analytics-service (aggregate serving views). */
  val Analytics: String = "chess.analytics"
