package chess.bench.wire

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*
import zio.json.*

import chess.api.{GameAnalysisDto, MoveAnalysisDto, OpeningDto}

/** Wire-format cost of the **post-game analysis** payload — the largest new DTO
  * the web-ui consumes (`GameAnalysisDto`, one `MoveAnalysisDto` per ply, each
  * carrying eval/win%/accuracy/glyph/bestMove + a PV list). The analysis compute
  * dwarfs the codec, but a full game's analysis is a big JSON document that
  * crosses gRPC (game-service → gateway, as `analysis_json`) and then HTTP
  * (gateway → browser), so its encode/decode/size is worth knowing.
  *
  * Fixture is a realistic ~60-ply game's analysis. `encodedBytes` reports the
  * serialized size (run with `-prof gc` to see allocation too).
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
class AnalysisDtoBenchmark:

  private val Plies = 60

  private val dto: GameAnalysisDto =
    val moves = (0 until Plies).toList.map { i =>
      MoveAnalysisDto(
        ply = i,
        color = if i % 2 == 0 then "white" else "black",
        san = "Nf3",
        evalCp = 15 - (i % 30),
        winPct = 50.0 + (i % 10),
        cpLoss = i % 25,
        accuracy = 90.0 - (i % 15),
        moveClass = if i % 7 == 0 then "Inaccuracy" else "Good",
        glyph = if i % 7 == 0 then Some("?!") else None,
        bestMove = "g1f3",
        pv = List("g1f3", "b8c6", "f1b5", "a7a6")
      )
    }
    GameAnalysisDto(
      opening = OpeningDto(Some("C70"), "Ruy Lopez", "Ruy Lopez", 8),
      moves = moves,
      accuracyWhite = 88.4,
      accuracyBlack = 85.1
    )

  private val json: String = dto.toJson

  @Benchmark def encode_json(): String = dto.toJson

  @Benchmark def decode_json(): GameAnalysisDto =
    json.fromJson[GameAnalysisDto].fold(e => throw new RuntimeException(e), identity)

  /** Serialized size in bytes — not a timing, a payload-size probe surfaced via
    * the returned value (eyeball it, or `-prof gc` for alloc).
    */
  @Benchmark def encoded_size(): Int = dto.toJson.length
