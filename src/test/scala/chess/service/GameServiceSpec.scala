package chess.service

import chess.model.GameEvent
import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import chess.repository.InMemoryGameRepository
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*

class GameServiceSpec extends AnyFlatSpec with Matchers:

  private val appLayer: ULayer[GameService] =
    InMemoryGameRepository.layer >>> GameServiceLive.layer

  private def run[A](task: ZIO[GameService, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(task.provide(appLayer))
        .getOrThrowFiberFailure()
    }

  private def runFailing[A](
      task: ZIO[GameService, Throwable, A]
  ): Exit[Throwable, A] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(task.provide(appLayer))
    }

  "GameService.newGame" should "return a GameStarted event with initial state" in:
    val event = run(GameService.newGame())
    event.initialState shouldBe GameState.initial
    event.gameId should not be empty

  it should "persist the initial state so getState returns it" in:
    val result = run(
      for
        event <- GameService.newGame()
        state <- GameService.getState(event.gameId)
      yield state
    )
    result shouldBe Some(GameState.initial)

  "GameService.makeMove" should "return a MoveMade event and updated state on a valid move" in:
    val (state, event) = run(
      for
        started <- GameService.newGame()
        stateAndEvent <- GameService.makeMove(started.gameId, "e2 e4")
      yield stateAndEvent
    )
    event shouldBe a[GameEvent.MoveMade]
    state.board.get(Position('e', 4)) shouldBe Some(
      Piece(Color.White, PieceType.Pawn)
    )

  it should "persist the updated state after a valid move" in:
    val stored = run(
      for
        started <- GameService.newGame()
        _ <- GameService.makeMove(started.gameId, "e2 e4")
        state <- GameService.getState(started.gameId)
      yield state
    )
    stored.get.board.get(Position('e', 4)) shouldBe Some(
      Piece(Color.White, PieceType.Pawn)
    )

  it should "fail for an illegal move" in:
    val exit = runFailing(
      for
        started <- GameService.newGame()
        result <- GameService.makeMove(started.gameId, "e2 e5")
      yield result
    )
    exit.isFailure shouldBe true

  it should "fail for malformed input" in:
    val exit = runFailing(
      for
        started <- GameService.newGame()
        result <- GameService.makeMove(started.gameId, "garbage")
      yield result
    )
    exit.isFailure shouldBe true

  it should "accept SAN pawn push notation" in:
    val (state, _) = run(
      for
        started <- GameService.newGame()
        result <- GameService.makeMove(started.gameId, "e4")
      yield result
    )
    state.board.get(Position('e', 4)) shouldBe Some(
      Piece(Color.White, PieceType.Pawn)
    )

  it should "accept SAN knight move notation" in:
    val (state, _) = run(
      for
        started <- GameService.newGame()
        result <- GameService.makeMove(started.gameId, "Nf3")
      yield result
    )
    state.board.get(Position('f', 3)) shouldBe Some(
      Piece(Color.White, PieceType.Knight)
    )

  it should "accept coordinate notation without separator" in:
    val (state, _) = run(
      for
        started <- GameService.newGame()
        result <- GameService.makeMove(started.gameId, "e2e4")
      yield result
    )
    state.board.get(Position('e', 4)) shouldBe Some(
      Piece(Color.White, PieceType.Pawn)
    )

  it should "fail for SAN castling since it is not yet implemented" in:
    runFailing(
      for
        started <- GameService.newGame()
        result <- GameService.makeMove(started.gameId, "O-O")
      yield result
    ).isFailure shouldBe true

  it should "fail when the game id does not exist" in:
    runFailing(
      GameService.makeMove("nonexistent", "e2 e4")
    ).isFailure shouldBe true

  "GameService.getState" should "return None for an unknown game id" in:
    run(GameService.getState("unknown")) shouldBe None
