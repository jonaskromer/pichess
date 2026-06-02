package chess.gameservice

import chess.codec.FenSerializer
import chess.model.{GameError, GameId, SessionState}
import chess.notation.SanSerializer
import io.grpc.{Status, StatusException}
import pichess.game_service.{MoveLogEntry, StateReply}
import zio.UIO

/** Pure mapping helpers for the gRPC service.
  *
  * Extracted out of [[GrpcServer]] so the GameError → Status mapping and
  * SessionState → StateReply projection are unit-testable independent of a
  * running gRPC channel. GrpcServer itself stays behind a coverage
  * exclusion (the rpc methods need an actual stub to drive).
  */
object GrpcMappers:

  /** Project a SessionState into the wire reply. Derives the SAN log from
    * the session's initial state + history; `deriveMoveLog`'s
    * `Either[String, …]` failure is `orDie`-d because reaching this code
    * implies the history is already legal (the controller only stores
    * verified moves).
    */
  def toStateReply(
      gameId: GameId,
      session: SessionState
  ): UIO[StateReply] =
    SanSerializer
      .deriveMoveLog(session.initialState, session.history)
      .orDie
      .map { log =>
        StateReply(
          gameId      = gameId,
          fen         = FenSerializer.serialize(session.state),
          status      = session.state.status.toString,
          activeColor = session.state.activeColor.toString,
          moveLog     = log.map((color, san) => MoveLogEntry(color.toString, san)),
          error       = session.error.getOrElse("")
        )
      }

  /** Lift a domain GameError into the gRPC status it should surface as.
    * The mapping is exhaustive across `GameError`'s variants — a new
    * variant becomes a `match`-not-exhaustive compile error here.
    */
  def toStatusException(err: GameError): StatusException =
    val status = err match
      case _: GameError.GameNotFound        => Status.NOT_FOUND
      case _: GameError.InvalidMove         => Status.INVALID_ARGUMENT
      case _: GameError.ParseError          => Status.INVALID_ARGUMENT
      case _: GameError.InfrastructureError => Status.INTERNAL
    new StatusException(status.withDescription(err.message))
