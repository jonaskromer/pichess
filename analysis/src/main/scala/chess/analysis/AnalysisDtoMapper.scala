package chess.analysis

import chess.api.{GameAnalysisDto, MoveAnalysisDto, OpeningDto}
import chess.codec.Nag

/** Maps the engine analysis result to the cross-compiled wire DTO, resolving
  * each move class to its NAG glyph for the GUI.
  */
object AnalysisDtoMapper:

  def toDto(analysis: GameAnalysis): GameAnalysisDto =
    GameAnalysisDto(
      opening = OpeningDto(
        analysis.opening.eco,
        analysis.opening.name,
        analysis.opening.family,
        analysis.opening.plyMatched
      ),
      moves = analysis.moves.map(moveDto),
      accuracyWhite = analysis.accuracyWhite,
      accuracyBlack = analysis.accuracyBlack
    )

  private def moveDto(m: MoveAnalysis): MoveAnalysisDto =
    MoveAnalysisDto(
      ply = m.ply,
      color = m.color,
      san = m.san,
      evalCp = m.evalCp,
      winPct = m.winPct,
      cpLoss = m.cpLoss,
      accuracy = m.accuracy,
      moveClass = m.moveClass.toString,
      glyph = m.moveClass.nag.flatMap(Nag.symbol),
      bestMove = m.bestMove,
      pv = m.pv
    )
