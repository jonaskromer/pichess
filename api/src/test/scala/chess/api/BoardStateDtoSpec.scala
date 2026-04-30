package chess.api

import zio.json.*
import zio.test.*

object BoardStateDtoSpec extends ZIOSpecDefault:

  private val sampleSquare =
    SquareDto("e4", "light", Some("pawn"), Some("white"))

  private val emptySquare =
    SquareDto("e5", "dark", None, None)

  private val sampleState = BoardStateDto(
    squares = List(sampleSquare, emptySquare),
    activeColor = "white",
    moveLog = List(MoveEntryDto("white", "e4")),
    error = None,
    inCheck = false,
    checkedKingPos = None,
    status = GameStatusDto.Playing
  )

  def spec = suite("BoardStateDto")(
    test("round-trip via JSON") {
      val json = sampleState.toJson
      val decoded = json.fromJson[BoardStateDto]
      assertTrue(decoded == Right(sampleState))
    },
    test("serialize None fields as explicit null") {
      val json = sampleState.toJson
      assertTrue(
        json.contains("\"error\":null"),
        json.contains("\"checkedKingPos\":null")
      )
    },
    test("round-trip a square with a piece") {
      assertTrue(
        sampleSquare.toJson.fromJson[SquareDto] == Right(sampleSquare)
      )
    },
    test("round-trip an empty square with null piece fields") {
      assertTrue(
        emptySquare.toJson.fromJson[SquareDto] == Right(emptySquare)
      )
    },
    test("round-trip a move entry") {
      val entry = MoveEntryDto("black", "Nf6")
      assertTrue(
        entry.toJson.fromJson[MoveEntryDto] == Right(entry)
      )
    },
    test("round-trip a move request") {
      val req = MoveRequest("e2 e4")
      assertTrue(
        req.toJson.fromJson[MoveRequest] == Right(req)
      )
    },
    test("round-trip an error DTO") {
      val err = ErrorDto("invalid move")
      assertTrue(
        err.toJson.fromJson[ErrorDto] == Right(err)
      )
    },
    test("decode state where optional fields are present") {
      val withError = sampleState.copy(
        error = Some("nope"),
        inCheck = true,
        checkedKingPos = Some("e1")
      )
      val json = withError.toJson
      val decoded = json.fromJson[BoardStateDto]
      assertTrue(decoded == Right(withError))
    },
    test("serialize a Playing status with null winner and reason") {
      val json = sampleState.toJson
      assertTrue(
        json.contains(
          """"status":{"kind":"playing","winner":null,"reason":null}"""
        )
      )
    },
    test("round-trip a Checkmate status") {
      val mated = sampleState.copy(status = GameStatusDto.checkmate("black"))
      val json = mated.toJson
      val decoded = json.fromJson[BoardStateDto]
      assertTrue(
        json.contains(""""kind":"checkmate""""),
        json.contains(""""winner":"black""""),
        decoded == Right(mated)
      )
    },
    test("round-trip a Draw status with a reason") {
      val drawn = sampleState.copy(status = GameStatusDto.draw("stalemate"))
      val json = drawn.toJson
      val decoded = json.fromJson[BoardStateDto]
      assertTrue(
        json.contains(""""kind":"draw""""),
        json.contains(""""reason":"stalemate""""),
        decoded == Right(drawn)
      )
    },
    test("round-trip a Resignation status with the surviving winner") {
      val resigned =
        sampleState.copy(status = GameStatusDto.resignation("white"))
      val json = resigned.toJson
      val decoded = json.fromJson[BoardStateDto]
      assertTrue(
        json.contains(""""kind":"resignation""""),
        json.contains(""""winner":"white""""),
        decoded == Right(resigned)
      )
    }
  )
