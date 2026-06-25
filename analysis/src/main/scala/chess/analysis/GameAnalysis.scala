package chess.analysis

import chess.opening.Opening

/** Analysis of one played move. Evals are **white-relative** (for the eval bar /
  * graph); `cpLoss` and `accuracy` are from the mover's perspective.
  */
final case class MoveAnalysis(
    ply: Int,
    color: String,
    san: String,
    evalCp: Int,
    winPct: Double,
    cpLoss: Int,
    accuracy: Double,
    moveClass: MoveClass,
    bestMove: String,
    pv: List[String]
)

/** Full post-game analysis: the named opening, per-move ratings, and per-side
  * accuracy.
  */
final case class GameAnalysis(
    opening: Opening,
    moves: List[MoveAnalysis],
    accuracyWhite: Double,
    accuracyBlack: Double
)
