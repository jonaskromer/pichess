package chess.model.rules

import chess.model.piece.PieceType
import chess.model.board.{Board, Position}

case class Ray(dc: Int, dr: Int, maxLen: Int, slides: Boolean)

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
      Ray(dc, dr, 1, slides = true)
    ),
    PieceType.Queen -> allDirections.map((dc, dr) =>
      Ray(dc, dr, 7, slides = true)
    ),
    PieceType.Rook -> orthogonal.map((dc, dr) => Ray(dc, dr, 7, slides = true)),
    PieceType.Bishop -> diagonal.map((dc, dr) => Ray(dc, dr, 7, slides = true)),
    PieceType.Knight -> knightOffsets.map((dc, dr) =>
      Ray(dc, dr, 1, slides = false)
    )
  )

  /** Walk a ray from `origin`, returning all reachable squares (stopping at
    * first occupied).
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
          if !ray.slides then
            // Knight/King-like: single hop, no path obstruction
            (pos :: acc).reverse
          else if board.contains(pos) then
            // Sliding piece hits an occupied square — include it (potential capture), then stop
            (pos :: acc).reverse
          else loop(c, r, step + 1, pos :: acc)
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
