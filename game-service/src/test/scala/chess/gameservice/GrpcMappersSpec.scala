package chess.gameservice

import io.grpc.Status
import zio.*
import zio.stream.SubscriptionRef
import zio.test.*

import chess.api.{BoardStateDto, ClockDto}
import chess.controller.GameController
import chess.events.{GameEventProducer, InMemoryGameEventProducer}
import chess.model.board.{GameState, GameStatus, Position}
import chess.model.piece.{Color, Piece, PieceType}
import chess.model.{ClockState, GameError, GameSnapshot, SessionState}
import chess.persistence.InMemoryGameRepository
import chess.service.{GameService, GameServiceLive}

object GrpcMappersSpec extends ZIOSpecDefault:

  private val gameId = "g1"
  private val session = SessionState(
    GameSnapshot.fresh(gameId, GameState.initial)
  )

  private val deps: ULayer[GameService & GameEventProducer] =
    ZLayer.make[GameService & GameEventProducer](
      GameServiceLive.layer,
      InMemoryGameRepository.layer,
      InMemoryGameEventProducer.layer
    )

  /** Play one ply so the resulting session has a non-empty history — needed to
    * exercise the `moveLog.map(...)` lambda inside `toStateReply`.
    */
  private val sessionWithOneMove: ZIO[
    GameService & GameEventProducer,
    Throwable,
    SessionState
  ] =
    for
      gs <- ZIO.service[GameService]
      producer <- ZIO.service[GameEventProducer]
      event <- gs.newGame()
      ref <- SubscriptionRef.make(
        SessionState(GameSnapshot.fresh(event.gameId, event.initialState))
      )
      _ <- GameController.makeMove(gs, producer, ref, "e2 e4")
      s <- ref.get
    yield s

  /** Play two plies so replay has a multi-frame history (initial + 2). */
  private val sessionWithTwoMoves: ZIO[
    GameService & GameEventProducer,
    Throwable,
    SessionState
  ] =
    for
      gs <- ZIO.service[GameService]
      producer <- ZIO.service[GameEventProducer]
      event <- gs.newGame()
      ref <- SubscriptionRef.make(
        SessionState(GameSnapshot.fresh(event.gameId, event.initialState))
      )
      _ <- GameController.makeMove(gs, producer, ref, "e2 e4")
      _ <- GameController.makeMove(gs, producer, ref, "e7 e5")
      s <- ref.get
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
      test(
        "projects gameId + FEN sidecar + a decodable BoardStateDto payload"
      ) {
        for
          reply <- GrpcMappers.toStateReply(gameId, session)
          dto <- ZIO.attempt(
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
          // Untimed session ⇒ no clock on the wire.
          dto.clock.isEmpty
        )
      },
      test("carries the authoritative clock for a timed session") {
        val timed = session.copy(clock =
          Some(ClockState(295000, 300000, 2000, runningSince = Some(0)))
        )
        for
          reply <- GrpcMappers.toStateReply(gameId, timed)
          dto <- ZIO.attempt(
            BoardStateDto.decodeBytes(reply.boardState.toByteArray)
          )
        yield assertTrue(
          dto.clock.exists(c =>
            c.whiteMs == 295000 && c.blackMs == 300000 &&
              // White is to move on the initial position, so its clock runs.
              c.runningFor.contains("white")
          )
        )
      },
      test("maps a timeout terminal onto the wire status") {
        val timedOut = SessionState(
          GameSnapshot.fresh(
            gameId,
            GameState.initial.endWith(GameStatus.Timeout(Color.Black))
          )
        )
        for
          reply <- GrpcMappers.toStateReply(gameId, timedOut)
          dto <- ZIO.attempt(
            BoardStateDto.decodeBytes(reply.boardState.toByteArray)
          )
        yield assertTrue(
          dto.status.kind == "timeout",
          dto.status.winner.contains("black")
        )
      },
      test("propagates session.error onto the reply envelope AND the DTO") {
        val withErr = session.copy(error = Some("boom"))
        for
          reply <- GrpcMappers.toStateReply(gameId, withErr)
          dto <- ZIO.attempt(
            BoardStateDto.decodeBytes(reply.boardState.toByteArray)
          )
        yield assertTrue(
          reply.error == "boom",
          dto.error.contains("boom")
        )
      },
      test("derives a SAN move log entry per ply in the session history") {
        for
          s <- sessionWithOneMove
          reply <- GrpcMappers.toStateReply(gameId, s)
          dto <- ZIO.attempt(
            BoardStateDto.decodeBytes(reply.boardState.toByteArray)
          )
        yield assertTrue(
          dto.moveLog.size == 1,
          dto.moveLog.head.san == "e4",
          dto.moveLog.head.color == "white"
        )
      }.provideLayer(deps)
    ),
    suite("replayFrames")(
      test("no moves ⇒ a single initial-position frame") {
        val frames = GrpcMappers.replayFrames(session)
        for dto <- ZIO.attempt(
            BoardStateDto.decodeBytes(frames.head.boardState.toByteArray)
          )
        yield assertTrue(
          frames.size == 1,
          frames.head.moveIndex == 0,
          frames.head.san.isEmpty,
          dto.moveLog.isEmpty,
          dto.activeColor == "white",
          dto.squares.size == 64
        )
      },
      test("N moves ⇒ N+1 frames, oldest first, each carrying its position") {
        for
          s <- sessionWithTwoMoves
          frames = GrpcMappers.replayFrames(s)
          decoded <- ZIO.foreach(frames)(f =>
            ZIO.attempt(BoardStateDto.decodeBytes(f.boardState.toByteArray))
          )
        yield assertTrue(
          frames.map(_.moveIndex) == List(0, 1, 2),
          frames.map(_.san) == List("", "e4", "e5"),
          // Initial frame: empty log, White to move.
          decoded(0).moveLog.isEmpty,
          decoded(0).activeColor == "white",
          // After 1.e4 → one logged move, Black to move.
          decoded(1).moveLog.map(_.san) == List("e4"),
          decoded(1).activeColor == "black",
          // After 1…e5 → both moves logged, White to move again.
          decoded(2).moveLog.map(_.san) == List("e4", "e5"),
          decoded(2).activeColor == "white"
        )
      }.provideLayer(deps)
    ),
    suite("toClockDto")(
      test("names the running side-to-move in runningFor") {
        val c = ClockState(100, 200, 0, runningSince = Some(0))
        assertTrue(
          GrpcMappers
            .toClockDto(c, Color.White) == ClockDto(100, 200, Some("white")),
          GrpcMappers.toClockDto(c, Color.Black).runningFor.contains("black")
        )
      },
      test("a paused clock has no running side") {
        val c = ClockState(100, 200, 0, runningSince = None)
        assertTrue(GrpcMappers.toClockDto(c, Color.White).runningFor.isEmpty)
      }
    ),
    suite("botMoveBudgetMs")(
      test("untimed game ⇒ no budget (caller uses fixed search depth)") {
        assertTrue(GrpcMappers.botMoveBudgetMs(None, Color.White, 0).isEmpty)
      },
      test("timed game ⇒ a flag-safe positive budget from the bot's clock") {
        val c = ClockState(300000, 300000, 2000, runningSince = Some(0))
        assertTrue(
          GrpcMappers
            .botMoveBudgetMs(Some(c), Color.White, 0)
            .exists(ms => ms > 0 && ms < 300000)
        )
      }
    ),
    suite("buildAnnotations")(
      test("surfaces a threatened own piece and its attackers") {
        // White to move; the Black pawn on d6 attacks White's knight on e5.
        val threatened = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King),
            Position('e', 5) -> Piece(Color.White, PieceType.Knight),
            Position('d', 6) -> Piece(Color.Black, PieceType.Pawn)
          ),
          Color.White
        )
        for ann <- GrpcMappers.buildAnnotations(threatened)
        yield assertTrue(
          ann.threats.contains("e5"),
          ann.attackersOf.get("e5").exists(_.contains("d6"))
        )
      }
    )
  ) @@ TestAspect.withLiveClock
