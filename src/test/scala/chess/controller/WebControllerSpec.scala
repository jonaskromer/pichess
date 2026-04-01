package chess.controller

import chess.model.board.GameState
import chess.model.piece.{Color, Piece, PieceType}
import chess.model.board.Position
import chess.repository.InMemoryGameRepository
import chess.service.{GameService, GameServiceLive}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.*

class WebControllerSpec extends AnyFlatSpec with Matchers:

  private val appLayer: ULayer[GameService] =
    InMemoryGameRepository.layer >>> GameServiceLive.layer

  private def run[A](task: ZIO[GameService, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(task.provide(appLayer))
        .getOrThrowFiberFailure()
    }

  "extractMove" should "parse a move from valid JSON" in:
    WebController.extractMove("""{"move":"e2 e4"}""") shouldBe Some("e2 e4")

  it should "parse a move with extra whitespace in JSON" in:
    WebController.extractMove("""{ "move" : "Nf3" }""") shouldBe Some("Nf3")

  it should "return None for JSON without a move field" in:
    WebController.extractMove("""{"other":"value"}""") shouldBe None

  it should "return None for empty string" in:
    WebController.extractMove("") shouldBe None

  it should "parse promotion notation" in:
    WebController.extractMove("""{"move":"e7 e8=Q"}""") shouldBe Some(
      "e7 e8=Q"
    )

  "SessionState" should "hold game state with empty defaults" in:
    val state =
      WebController.SessionState("id", GameState.initial, Nil, None)
    state.gameId shouldBe "id"
    state.state shouldBe GameState.initial
    state.moveLog shouldBe Nil
    state.error shouldBe None

  it should "hold game state with error" in:
    val state =
      WebController.SessionState("id", GameState.initial, Nil, Some("oops"))
    state.error shouldBe Some("oops")

  it should "hold game state with move log" in:
    val log = List((Color.White, "e4"), (Color.Black, "e5"))
    val state =
      WebController.SessionState("id", GameState.initial, log, None)
    state.moveLog shouldBe log
