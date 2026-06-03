package chess.controller

import com.google.protobuf.ByteString
import io.grpc.{Status as GrpcStatus, StatusException}
import pichess.game_service.StateReply
import zio.*
import zio.test.*

import chess.api.{AnnotationsDto, BoardStateDto, ErrorDto, GameStatusDto}

/** Direct unit tests for WebController's private[controller] helpers
  * that guard against contract-violating input from game-service. The
  * full-stack integration tests can't trigger these branches because
  * the real game-service is well-behaved — bumping each helper to
  * package-private lets us feed it synthetic bad input here.
  */
object WebControllerHelpersSpec extends ZIOSpecDefault:

  private val sampleDto = BoardStateDto(
    squares = Nil,
    activeColor = "white",
    moveLog = Nil,
    error = None,
    inCheck = false,
    checkedKingPos = None,
    status = GameStatusDto.Playing,
  )

  def spec = suite("WebController helpers")(
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
    suite("replyToDto bytes decode")(
      test("round-trips an encoded BoardStateDto through the wire bytes") {
        val reply = StateReply(
          gameId     = "g1",
          boardState = ByteString.copyFrom(BoardStateDto.encodeBytes(sampleDto)),
          error      = "",
          fen        = "",
        )
        for dto <- WebController.replyToDto(reply)
        yield assertTrue(dto == sampleDto)
      },
      test("fails with an ErrorDto when the bytes can't be decoded") {
        // Decode-error guard — unreachable through the full pipeline
        // (game-service always emits a valid Schema-encoded DTO) but
        // the guard exists so a malformed payload doesn't crash the
        // gateway.
        val reply = StateReply(
          gameId     = "g1",
          boardState = ByteString.copyFromUtf8("not a valid protobuf"),
          error      = "",
          fen        = "",
        )
        WebController.replyToDto(reply).either.map { result =>
          assertTrue(
            result.isLeft,
            result.left.exists(_.error.contains("decode StateReply.boardState"))
          )
        }
      }
    ),
    suite("decodeServerAnnotations bytes round-trip")(
      test("decodes a populated bundle into the cache shape") {
        val sample = AnnotationsDto(
          legalMovesFrom = Map("e2" -> List("e3", "e4")),
          threats        = List("d5"),
          attackersOf    = Map("d5" -> List("e6")),
        )
        val bytes = AnnotationsDto.encodeBytes(sample)
        val ann   = WebController.decodeServerAnnotations(bytes)
        assertTrue(
          ann.legalMovesFrom == sample.legalMovesFrom,
          ann.threats        == sample.threats,
          ann.attackersOf    == sample.attackersOf,
        )
      },
      test("an empty bundle round-trips cleanly") {
        val bytes = AnnotationsDto.encodeBytes(AnnotationsDto.Empty)
        val ann   = WebController.decodeServerAnnotations(bytes)
        assertTrue(
          ann.legalMovesFrom.isEmpty,
          ann.threats.isEmpty,
          ann.attackersOf.isEmpty,
        )
      },
    )
  )
