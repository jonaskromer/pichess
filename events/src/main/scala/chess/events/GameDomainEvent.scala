package chess.events

import zio.json.*

/** Domain events for chess games — published by `gameService` to the
  * `chess.game-events` Kafka topic, consumed by `repository` (write side) and
  * any future projections.
  *
  * `resultingFen` is the universal "what to persist after this event"
  * field: every event leaves the game in a single canonical state, and the
  * repository consumer maps `event.resultingFen` to `repo.save(gameId, fen)`
  * regardless of event type.
  *
  * Event-specific metadata (move SAN, draw reason, winner colour, …) is
  * carried alongside `resultingFen` for future analytical consumers but is not
  * required by the repository.
  */
@jsonDiscriminator("type")
sealed trait GameDomainEvent:
  def gameId: String
  def resultingFen: String
  def occurredAt: Long

object GameDomainEvent:

  final case class GameStarted(
      gameId: String,
      resultingFen: String,
      occurredAt: Long
  ) extends GameDomainEvent

  final case class GameLoaded(
      gameId: String,
      resultingFen: String,
      initialFen: String,
      historyMoves: Int,
      occurredAt: Long
  ) extends GameDomainEvent

  final case class MoveMade(
      gameId: String,
      resultingFen: String,
      moveCoord: String,
      san: String,
      occurredAt: Long
  ) extends GameDomainEvent

  final case class Undone(
      gameId: String,
      resultingFen: String,
      occurredAt: Long
  ) extends GameDomainEvent

  final case class Redone(
      gameId: String,
      resultingFen: String,
      occurredAt: Long
  ) extends GameDomainEvent

  final case class DrawClaimed(
      gameId: String,
      resultingFen: String,
      reason: String,
      occurredAt: Long
  ) extends GameDomainEvent

  final case class Forfeited(
      gameId: String,
      resultingFen: String,
      winner: String,
      occurredAt: Long
  ) extends GameDomainEvent

  final case class GameEnded(
      gameId: String,
      resultingFen: String,
      status: String,
      occurredAt: Long
  ) extends GameDomainEvent

  given JsonEncoder[GameDomainEvent] = DeriveJsonEncoder.gen
  given JsonDecoder[GameDomainEvent] = DeriveJsonDecoder.gen
