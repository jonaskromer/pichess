package chess.gameservice

import chess.api.{BoardStateDto, WebBoardView}
import chess.model.{GameError, GameId, SessionState}
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusException}
import pichess.game_service.StateReply
import zio.UIO
import zio.ZIO

/** Pure mapping helpers for the gRPC service.
  *
  * Extracted out of [[GrpcServer]] so the GameError → Status mapping and
  * SessionState → StateReply projection are unit-testable independent of a
  * running gRPC channel. GrpcServer itself stays behind a coverage
  * exclusion (the rpc methods need an actual stub to drive).
  */
object GrpcMappers:

  /** Project a SessionState into the wire reply. The DTO is built here
    * (server-side) using [[WebBoardView.toDto]] — which used to live in
    * the gateway — and serialised via [[BoardStateDto.protobufCodec]].
    * The gateway just decodes the bytes back into the same DTO and
    * forwards it as JSON; no more FEN-parse round-trip on the reply
    * path.
    *
    * The SAN log is read from the pre-computed
    * [[chess.model.GameSnapshot.moveLog]] field — incrementally
    * maintained by `recordMove` / `undoOnce` / `redoOnce` and seeded by
    * `fromHistory` on load — so this projection avoids re-walking the
    * history through `SanSerializer.deriveMoveLog` on every reply.
    */
  def toStateReply(
      gameId: GameId,
      session: SessionState
  ): UIO[StateReply] =
    ZIO.succeed {
      val dto = WebBoardView.toDto(
        state   = session.state,
        moveLog = session.game.moveLog,
        error   = session.error,
      )
      StateReply(
        gameId     = gameId,
        boardState = encodeBoardState(dto),
        error      = session.error.getOrElse(""),
        fen        = chess.codec.FenSerializer.serialize(session.state),
      )
    }

  /** Encode a [[BoardStateDto]] to the bytes carried by
    * [[StateReply.boardState]]. Delegates to [[BoardStateDto.encodeBytes]]
    * (boopickle binary codec — picked over zio-schema-protobuf after
    * the latter benched 33× slower on this DTO shape).
    */
  def encodeBoardState(dto: BoardStateDto): ByteString =
    ByteString.copyFrom(BoardStateDto.encodeBytes(dto))

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
