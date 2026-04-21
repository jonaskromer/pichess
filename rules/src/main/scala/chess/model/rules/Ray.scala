package chess.model.rules

import chess.model.piece.PieceType
import chess.model.board.{Board, Position}

/** Behavior of a piece along a ray.
  *
  *   - [[Slider]] — Queen, Rook, Bishop, King: advances step-by-step along
  *     the ray and stops at the first occupied square (which becomes a
  *     potential capture target). Blocked by intervening pieces.
  *   - [[Leaper]] — Knight: lands directly on the destination regardless of
  *     squares in between. Not blocked by intervening pieces.
  */
enum RayKind:
  case Slider
  case Leaper

case class Ray(dc: Int, dr: Int, maxLen: Int, kind: RayKind)

object Ray:

  private val orthogonal = List((1, 0), (-1, 0), (0, 1), (0, -1))
  private val diagonal = List((1, 1), (1, -1), (-1, 1), (-1, -1))
  private val allDirections = orthogonal ++ diagonal

  private val knightOffsets = List(
    (1, 2),
    (2, 1),
    (2, -1),
    (1, -2),
    (-1, -2),
    (-2, -1),
    (-2, 1),
    (-1, 2)
  )

  val table: Map[PieceType, List[Ray]] = Map(
    PieceType.King -> allDirections.map((dc, dr) =>
      Ray(dc, dr, 1, RayKind.Slider)
    ),
    PieceType.Queen -> allDirections.map((dc, dr) =>
      Ray(dc, dr, 7, RayKind.Slider)
    ),
    PieceType.Rook -> orthogonal.map((dc, dr) =>
      Ray(dc, dr, 7, RayKind.Slider)
    ),
    PieceType.Bishop -> diagonal.map((dc, dr) =>
      Ray(dc, dr, 7, RayKind.Slider)
    ),
    PieceType.Knight -> knightOffsets.map((dc, dr) =>
      Ray(dc, dr, 1, RayKind.Leaper)
    )
  )

  /** Walk a ray from `origin`, returning all reachable squares (stopping at
    * first occupied for sliders).
    */
  def walk(board: Board, origin: Position, ray: Ray): List[Position] =
    def loop(
        col: Int,
        row: Int,
        step: Int,
        acc: List[Position]
    ): List[Position] =
      if step > ray.maxLen then acc.reverse
      else
        val c = col + ray.dc
        val r = row + ray.dr
        if c < 'a' || c > 'h' || r < 1 || r > 8 then acc.reverse
        else
          val pos = Position(c.toChar, r)
          ray.kind match
            case RayKind.Leaper =>
              // Lands directly; intervening occupation is irrelevant.
              (pos :: acc).reverse
            case RayKind.Slider if board.contains(pos) =>
              // Hit an occupied square — include it (potential capture), stop.
              (pos :: acc).reverse
            case RayKind.Slider =>
              loop(c, r, step + 1, pos :: acc)
    loop(origin.col, origin.row, 1, Nil)

  /** Check if a piece at `from` can reach `target` via any of its rays. */
  def canReach(
      board: Board,
      from: Position,
      pieceType: PieceType,
      target: Position
  ): Boolean =
    table(pieceType).exists { ray =>
      walk(board, from, ray).contains(target)
    }
