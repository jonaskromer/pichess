package chess.bench

import boopickle.Default.*
import chess.api.{BoardStateDto, SquareDto, WebBoardView}
import chess.codec.{FenParserRegex, FenSerializer}
import chess.model.board.GameState
import org.openjdk.jmh.annotations.*
import zio.json.*

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/** Codec round-trip cost across two payload sizes — small (a single
  * `SquareDto`) and medium (the full `BoardStateDto` carrying 64
  * squares + nested types). Compares the candidate codecs that could
  * back a "bytes-DTO over gRPC" pattern:
  *
  *   - FEN (hand-tuned, baseline; medium only — small isn't a thing
  *     in FEN)
  *   - zio-json (text)
  *   - boopickle (Scala-native binary)
  *
  * zio-schema-protobuf was originally in this comparison but benched
  * 33× slower than FEN on the medium payload, so it's been dropped
  * from the production wire format and from the bench. The interesting
  * question now is whether boopickle stays within tier of
  * FEN/zio-json across payload sizes — answering "does the
  * bytes-DTO pattern hold up if we apply it elsewhere?" (the user's
  * dead-end check).
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
class BoardStateDtoBenchmark:

  // ---- Medium payload: full BoardStateDto (64 squares + nested) -----
  private val state: GameState     = GameState.initial
  private val mediumDto: BoardStateDto = WebBoardView.toDto(state, Nil, None)
  private val mediumFen: String        = FenSerializer.serialize(state)
  private val mediumJson: String       = mediumDto.toJson
  private val mediumBoopickle: Array[Byte] = picked(mediumDto)

  // ---- Small payload: single SquareDto --------------------------------
  private val smallDto: SquareDto =
    SquareDto("e4", "dark", Some("pawn"), Some("white"))
  private val smallJson: String           = smallDto.toJson
  private val smallBoopickle: Array[Byte] = picked(smallDto)

  private def picked[A: Pickler](a: A): Array[Byte] =
    val buf = Pickle.intoBytes(a)
    val arr = new Array[Byte](buf.remaining)
    buf.get(arr)
    arr

  // -- Encode: medium payload (BoardStateDto) ---------------------------

  @Benchmark def encode_medium_fen(): String  = FenSerializer.serialize(state)
  @Benchmark def encode_medium_json(): String = mediumDto.toJson
  @Benchmark def encode_medium_boopickle(): Array[Byte] = picked(mediumDto)

  // -- Decode: medium payload -------------------------------------------
  // FEN "decode" includes WebBoardView.toDto so the result is a usable
  // BoardStateDto (matches the bytes paths' end-state).

  @Benchmark def decode_medium_fen(): BoardStateDto =
    val parsed = UnsafeRuntime.run(FenParserRegex.parse(mediumFen))
    WebBoardView.toDto(parsed, Nil, None)

  @Benchmark def decode_medium_json(): BoardStateDto =
    mediumJson
      .fromJson[BoardStateDto]
      .fold(err => throw new RuntimeException(err), identity)

  @Benchmark def decode_medium_boopickle(): BoardStateDto =
    Unpickle[BoardStateDto].fromBytes(ByteBuffer.wrap(mediumBoopickle))

  // -- Encode: small payload (SquareDto) --------------------------------

  @Benchmark def encode_small_json(): String           = smallDto.toJson
  @Benchmark def encode_small_boopickle(): Array[Byte] = picked(smallDto)

  // -- Decode: small payload --------------------------------------------

  @Benchmark def decode_small_json(): SquareDto =
    smallJson
      .fromJson[SquareDto]
      .fold(err => throw new RuntimeException(err), identity)

  @Benchmark def decode_small_boopickle(): SquareDto =
    Unpickle[SquareDto].fromBytes(ByteBuffer.wrap(smallBoopickle))
