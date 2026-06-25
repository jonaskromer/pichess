package chess.analysis

import zio.*

import chess.api.GameAnalysisDto
import chess.codec.PgnParser
import chess.model.GameError

/** The callable analysis unit: parse a PGN, rate it with [[GameAnalyzer]] at the
  * requested depth, and return the wire DTO. Transport-agnostic — a gRPC
  * handler, an HTTP route, or a test can all drive it.
  */
final class AnalysisService(analyzer: GameAnalyzer):

  def analyze(pgn: String, depth: Int): IO[GameError, GameAnalysisDto] =
    for
      game     <- PgnParser.parse(pgn)
      analysis <- analyzer.analyze(game.initialState, game.history, depth)
    yield AnalysisDtoMapper.toDto(analysis)

/** Memoizes analyses by `(pgn, depth)` so re-opening a game is instant
  * (on-demand + cache, per the plan). Idempotent: the same request computes once.
  */
final class CachedAnalysisService(
    underlying: AnalysisService,
    cache: Ref[Map[(String, Int), GameAnalysisDto]]
):
  def analyze(pgn: String, depth: Int): IO[GameError, GameAnalysisDto] =
    val key = (pgn, depth)
    cache.get.map(_.get(key)).flatMap {
      case Some(dto) => ZIO.succeed(dto)
      case None =>
        underlying.analyze(pgn, depth).tap(dto => cache.update(_ + (key -> dto)))
    }

object CachedAnalysisService:
  def make(underlying: AnalysisService): UIO[CachedAnalysisService] =
    Ref
      .make(Map.empty[(String, Int), GameAnalysisDto])
      .map(CachedAnalysisService(underlying, _))
