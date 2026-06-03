package chess.gameservice

import io.grpc.Status
import zio.*
import zio.stream.SubscriptionRef
import zio.test.*

import chess.api.BoardStateDto
import chess.controller.GameController
import chess.events.{GameEventProducer, InMemoryGameEventProducer}
import chess.model.board.GameState
import chess.model.{GameError, GameSnapshot, SessionState}
import chess.persistence.InMemoryGameRepository
import chess.service.{GameService, GameServiceLive}

object GrpcMappersSpec extends ZIOSpecDefault:

  private val gameId  = "g1"
  private val session = SessionState(GameSnapshot.fresh(gameId, GameState.initial))

  private val deps: ULayer[GameService & GameEventProducer] =
    ZLayer.make[GameService & GameEventProducer](
      GameServiceLive.layer,
      InMemoryGameRepository.layer,
      InMemoryGameEventProducer.layer
    )

  /** Play one ply so the resulting session has a non-empty history — needed
    * to exercise the `moveLog.map(...)` lambda inside `toStateReply`.
    */
  private val sessionWithOneMove: ZIO[
    GameService & GameEventProducer,
    Throwable,
    SessionState
  ] =
    for
      gs       <- ZIO.service[GameService]
      producer <- ZIO.service[GameEventProducer]
      event    <- gs.newGame()
      ref      <- SubscriptionRef.make(
                    SessionState(GameSnapshot.fresh(event.gameId, event.initialState))
                  )
      _        <- GameController.makeMove(gs, producer, ref, "e2 e4")
      s        <- ref.get
    yield s

  def spec = suite("GrpcMappers")(
    suite("toStatusException")(
      test("GameNotFound -> NOT_FOUND") {
        val ex = GrpcMappers.toStatusException(GameError.GameNotFound(gameId))
        assertTrue(
          ex.getStatus.getCode == Status.NOT_FOUND.getCode,
          ex.getStatus.getDescription == s"Game not found: $gameId"
        )
      },
      test("InvalidMove -> INVALID_ARGUMENT") {
        val ex = GrpcMappers.toStatusException(GameError.InvalidMove("bad"))
        assertTrue(
          ex.getStatus.getCode == Status.INVALID_ARGUMENT.getCode,
          ex.getStatus.getDescription == "bad"
        )
      },
      test("ParseError -> INVALID_ARGUMENT") {
        val ex = GrpcMappers.toStatusException(GameError.ParseError("oops"))
        assertTrue(
          ex.getStatus.getCode == Status.INVALID_ARGUMENT.getCode,
          ex.getStatus.getDescription == "oops"
        )
      },
      test("InfrastructureError -> INTERNAL") {
        val ex = GrpcMappers
          .toStatusException(GameError.InfrastructureError("down"))
        assertTrue(
          ex.getStatus.getCode == Status.INTERNAL.getCode,
          ex.getStatus.getDescription == "down"
        )
      }
    ),
    suite("toStateReply")(
      test("projects gameId + FEN sidecar + a decodable BoardStateDto payload") {
        for
          reply <- GrpcMappers.toStateReply(gameId, session)
          dto   <- ZIO.attempt(
                     BoardStateDto.decodeBytes(reply.boardState.toByteArray)
                   )
        yield assertTrue(
          reply.gameId == gameId,
          reply.fen.nonEmpty,
          reply.error.isEmpty,
          // Decoded DTO mirrors the session state
          dto.activeColor == "white",
          dto.status.kind == "playing",
          dto.moveLog.isEmpty,
          dto.squares.size == 64,
        )
      },
      test("propagates session.error onto the reply envelope AND the DTO") {
        val withErr = session.copy(error = Some("boom"))
        for
          reply <- GrpcMappers.toStateReply(gameId, withErr)
          dto   <- ZIO.attempt(
                     BoardStateDto.decodeBytes(reply.boardState.toByteArray)
                   )
        yield assertTrue(
          reply.error == "boom",
          dto.error.contains("boom"),
        )
      },
      test("derives a SAN move log entry per ply in the session history") {
        for
          s     <- sessionWithOneMove
          reply <- GrpcMappers.toStateReply(gameId, s)
          dto   <- ZIO.attempt(
                     BoardStateDto.decodeBytes(reply.boardState.toByteArray)
                   )
        yield assertTrue(
          dto.moveLog.size == 1,
          dto.moveLog.head.san == "e4",
          dto.moveLog.head.color == "white",
        )
      }.provideLayer(deps)
    )
  )
