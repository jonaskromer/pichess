package chess.api

import zio.json.*

/** Wire DTOs for post-game analysis — shared by the JVM producer (analysis
  * compute) and the Laminar web-ui (JS consumer). The opening's `eco` and each
  * move's `glyph` (NAG symbol, e.g. "??" / "!") are optional.
  */
final case class OpeningDto(
    eco: Option[String],
    name: String,
    family: String,
    plyMatched: Int
)
object OpeningDto:
  given JsonEncoder[OpeningDto] = DeriveJsonEncoder.gen[OpeningDto]
  given JsonDecoder[OpeningDto] = DeriveJsonDecoder.gen[OpeningDto]

final case class MoveAnalysisDto(
    ply: Int,
    color: String,
    san: String,
    evalCp: Int,
    winPct: Double,
    cpLoss: Int,
    accuracy: Double,
    moveClass: String,
    glyph: Option[String],
    bestMove: String,
    pv: List[String]
)
object MoveAnalysisDto:
  given JsonEncoder[MoveAnalysisDto] = DeriveJsonEncoder.gen[MoveAnalysisDto]
  given JsonDecoder[MoveAnalysisDto] = DeriveJsonDecoder.gen[MoveAnalysisDto]

final case class GameAnalysisDto(
    opening: OpeningDto,
    moves: List[MoveAnalysisDto],
    accuracyWhite: Double,
    accuracyBlack: Double
)
object GameAnalysisDto:
  given JsonEncoder[GameAnalysisDto] = DeriveJsonEncoder.gen[GameAnalysisDto]
  given JsonDecoder[GameAnalysisDto] = DeriveJsonDecoder.gen[GameAnalysisDto]

/** Request to analyze a game given as PGN (the depth the engine searches each
  * position; the service may clamp it).
  */
final case class AnalyzeRequestDto(pgn: String, depth: Int)
object AnalyzeRequestDto:
  given JsonEncoder[AnalyzeRequestDto] = DeriveJsonEncoder.gen[AnalyzeRequestDto]
  given JsonDecoder[AnalyzeRequestDto] = DeriveJsonDecoder.gen[AnalyzeRequestDto]
