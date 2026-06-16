package chess.model.rules

import chess.model.board.{Board, Position}
import chess.model.piece.PieceType

/** Behavior of a piece along a ray.
  *
  *   - [[Slider]] — Queen, Rook, Bishop, King: advances step-by-step along the
  *     ray and stops at the first occupied square (which becomes a potential
  *     capture target). Blocked by intervening pieces.
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

  /** Check if a piece at `from` can reach `target` via any of its rays.
    *
    * Allocation-free: walks each ray inline with early-return rather than
    * materialising the square list (the old `walk(...).contains(target)`
    * built a `List[Position]` per ray just to test membership — a hot
    * per-node alloc in move-legality checks). Same reachability result:
    * `target` is reachable iff it lies on a ray before/at the first
    * blocker (slider) or is the leaper's landing square. */
  def canReach(
      board: Board,
      from: Position,
      pieceType: PieceType,
      target: Position
  ): Boolean =
    var rs = table(pieceType)
    while rs.nonEmpty do
      if reaches(board, from, rs.head, target) then return true
      rs = rs.tail
    false

  private def reaches(board: Board, from: Position, ray: Ray, target: Position): Boolean =
    val tc = target.col.toInt
    val tr = target.row
    var c  = from.col.toInt
    var r  = from.row
    var step = 1
    while step <= ray.maxLen do
      c += ray.dc
      r += ray.dr
      if c < 'a'.toInt || c > 'h'.toInt || r < 1 || r > 8 then return false
      if c == tc && r == tr then return true // reached target
      ray.kind match
        case RayKind.Leaper => return false // lands elsewhere, not the target
        case RayKind.Slider =>
          // First occupied square before the target blocks the ray.
          if board.contains(Position(c.toChar, r)) then return false
      step += 1
    false
