package chess.gameservice

import chess.codec.{FenSerializer, JsonSerializer, PgnSerializer}
import chess.controller.GameController
import chess.events.GameEventProducer
import chess.model.{GameError, GameId, GameSnapshot, SessionState}
import chess.notation.SanSerializer
import chess.service.GameService
import io.grpc.{Status, StatusException}
import pichess.game_service.{
  ExportReply,
  ExportRequest,
  GameIdRequest,
  LoadGameRequest,
  MoveLogEntry,
  MoveRequest,
  NewGameRequest,
  StateReply,
  ZioGameService
}
import zio.*
import zio.stream.{Stream, SubscriptionRef, ZStream}

/** zio-grpc service implementation. Each rpc routes the request through the
  * existing in-process `GameService` / `GameController`, then projects the
  * resulting `SessionState` into a `StateReply`. Per-game atomicity comes
  * for free from `SubscriptionRef.modifyZIO`'s semaphore inside the
  * controllers.
  *
  * Errors:
  *   - `GameError.GameNotFound` → `Status.NOT_FOUND`
  *   - `GameError.InvalidMove` / `GameError.ParseError` → `Status.INVALID_ARGUMENT`
  *   - `GameError.InfrastructureError` → `Status.INTERNAL`
  */
final class GrpcServer(
    gs: GameService,
    producer: GameEventProducer,
    sessions: GameSessions
) extends ZioGameService.GameService:

  def newGame(request: NewGameRequest): IO[StatusException, StateReply] =
    (for
      event   <- gs.newGame()
      snapshot = GameSnapshot.fresh(event.gameId, event.initialState)
      ref     <- sessions.register(snapshot)
      reply   <- replyFor(event.gameId, ref)
    yield reply).mapError(toStatusException)

  def loadGame(request: LoadGameRequest): IO[StatusException, StateReply] =
    (for
      result <- gs.loadGame(request.raw)
      (event, history) = result
      snapshot =
        GameSnapshot.fromHistory(event.gameId, event.initialState, history.reverse)
      ref     <- sessions.register(snapshot)
      reply   <- replyFor(event.gameId, ref)
    yield reply).mapError(toStatusException)

  def makeMove(request: MoveRequest): IO[StatusException, StateReply] =
    runOn(request.gameId) { ref =>
      GameController.makeMove(gs, producer, ref, request.raw)
    }

  def undo(request: GameIdRequest): IO[StatusException, StateReply] =
    runOn(request.gameId)(GameController.undo(gs, producer, _))

  def redo(request: GameIdRequest): IO[StatusException, StateReply] =
    runOn(request.gameId)(GameController.redo(gs, producer, _))

  def claimDraw(request: GameIdRequest): IO[StatusException, StateReply] =
    runOn(request.gameId)(GameController.claimDraw(gs, producer, _))

  def forfeit(request: GameIdRequest): IO[StatusException, StateReply] =
    runOn(request.gameId)(GameController.forfeit(gs, producer, _))

  def getState(request: GameIdRequest): IO[StatusException, StateReply] =
    sessions
      .get(request.gameId)
      .flatMap(replyFor(request.gameId, _))
      .mapError(toStatusException)

  def exportGame(request: ExportRequest): IO[StatusException, ExportReply] =
    (for
      ref <- sessions.get(request.gameId)
      s   <- ref.get
      body <- request.format.toLowerCase match
                case "fen"  => ZIO.succeed(FenSerializer.serialize(s.state))
                case "json" => ZIO.succeed(JsonSerializer.serialize(s.state))
                case "pgn"  =>
                  SanSerializer
                    .deriveMoveLog(s.initialState, s.history)
                    .orDie
                    .flatMap(log => PgnSerializer.serialize(log, s.state.status))
                case other =>
                  ZIO.fail(
                    GameError.ParseError(
                      s"Unknown format '$other'; expected fen, pgn, or json"
                    )
                  )
    yield ExportReply(format = request.format.toLowerCase, body = body))
      .mapError(toStatusException)

  def subscribeGame(
      request: GameIdRequest
  ): Stream[StatusException, StateReply] =
    ZStream
      .fromZIO(sessions.get(request.gameId).mapError(toStatusException))
      .flatMap { ref =>
        ref.changes.mapZIO(state => toStateReply(request.gameId, state))
      }

  // ---- helpers ---------------------------------------------------------

  private def runOn(gameId: GameId)(
      action: SubscriptionRef[SessionState] => IO[GameError, Unit]
  ): IO[StatusException, StateReply] =
    (for
      ref <- sessions.get(gameId)
      _   <- action(ref)
      out <- replyFor(gameId, ref)
    yield out).mapError(toStatusException)

  private def replyFor(
      gameId: GameId,
      ref: SubscriptionRef[SessionState]
  ): UIO[StateReply] =
    ref.get.flatMap(toStateReply(gameId, _))

  private def toStateReply(
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

  private def toStatusException(err: GameError): StatusException =
    val status = err match
      case _: GameError.GameNotFound      => Status.NOT_FOUND
      case _: GameError.InvalidMove       => Status.INVALID_ARGUMENT
      case _: GameError.ParseError        => Status.INVALID_ARGUMENT
      case _: GameError.InfrastructureError => Status.INTERNAL
    new StatusException(status.withDescription(err.message))

object GrpcServer:
  val layer: URLayer[
    GameService & GameEventProducer & GameSessions,
    GrpcServer
  ] =
    ZLayer.fromFunction(GrpcServer(_, _, _))

  /** Layer exposing the impl as the public gRPC service trait — what
    * zio-grpc's `ZBindableService` derivation looks for, and what an
    * in-process gateway/test can wire as its client.
    */
  val asServiceLayer: URLayer[
    GameService & GameEventProducer & GameSessions,
    ZioGameService.GameService
  ] =
    ZLayer.fromFunction((gs: GameService, p: GameEventProducer, s: GameSessions) =>
      new GrpcServer(gs, p, s): ZioGameService.GameService
    )
