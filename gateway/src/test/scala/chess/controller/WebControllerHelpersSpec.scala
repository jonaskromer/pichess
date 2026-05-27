package chess.controller

import chess.api.ErrorDto
import chess.model.piece.Color
import io.grpc.{Status as GrpcStatus, StatusException}
import pichess.game_service.StateReply
import zio.*
import zio.test.*

/** Direct unit tests for WebController's private[controller] helpers
  * that guard against contract-violating input from game-service. The
  * full-stack integration tests can't trigger these branches because
  * the real game-service is well-behaved — bumping each helper to
  * package-private lets us feed it synthetic bad input here.
  */
object WebControllerHelpersSpec extends ZIOSpecDefault:

  def spec = suite("WebController helpers")(
    suite("parseColor fallback")(
      test("returns White for the documented `White` string") {
        assertTrue(WebController.parseColor("White") == Color.White)
      },
      test("returns Black for the documented `Black` string") {
        assertTrue(WebController.parseColor("Black") == Color.Black)
      },
      test("falls back to White on any other input (defensive arm)") {
        // game-service is contracted to emit only "White" / "Black";
        // hitting this branch in production would be a bug. The
        // fallback exists to keep the gateway from crashing on a
        // surprise value.
        assertTrue(
          WebController.parseColor("Mauve") == Color.White,
          WebController.parseColor("") == Color.White,
          WebController.parseColor("WHITE") == Color.White
        )
      }
    ),
    suite("toErrorDto null-description fallback")(
      test("uses the status description when present") {
        val err = new StatusException(
          GrpcStatus.INVALID_ARGUMENT.withDescription("explicit message")
        )
        assertTrue(WebController.toErrorDto(err) == ErrorDto("explicit message"))
      },
      test("falls back to err.getMessage when getDescription returns null") {
        // `GrpcStatus.INTERNAL` carries no description by default —
        // `getDescription` returns null, so the `getOrElse(getMessage)`
        // arm fires. `err.getMessage` is the gRPC-formatted status
        // string ("INTERNAL").
        val err  = new StatusException(GrpcStatus.INTERNAL)
        val dto  = WebController.toErrorDto(err)
        assertTrue(dto.error.contains("INTERNAL"))
      }
    ),
    suite("replyToDto FenParser failure")(
      test("succeeds on a valid FEN") {
        val reply = StateReply(
          gameId = "g1",
          fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
          status = "Playing",
          activeColor = "White",
          moveLog = Seq.empty,
          error = ""
        )
        for dto <- WebController.replyToDto(reply)
        yield assertTrue(dto.activeColor == "white")
      },
      test("fails with an ErrorDto when the FEN can't be parsed") {
        // FenParser-error guard — unreachable through the full pipeline
        // (game-service always emits valid FENs) but the guard exists
        // so a malformed payload doesn't crash the gateway.
        val reply = StateReply(
          gameId = "g1",
          fen = "this is not a fen",
          status = "Playing",
          activeColor = "White",
          moveLog = Seq.empty,
          error = ""
        )
        WebController.replyToDto(reply).either.map { result =>
          assertTrue(result.isLeft)
        }
      }
    ),
    suite("fenToAnnotations FenParser failure")(
      test("succeeds on the standard starting position") {
        for ann <- WebController.fenToAnnotations(
                     "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
                   )
        yield assertTrue(ann.legalMovesFrom.nonEmpty, ann.threats.isEmpty)
      },
      test("fails with an ErrorDto on a malformed FEN (defensive guard)") {
        WebController.fenToAnnotations("nope").either.map { result =>
          assertTrue(result.isLeft)
        }
      }
    )
  )
