package chess.bot.engine.internal

import chess.model.board.{MoveInt, Position, PositionView}
import chess.model.piece.{Color, PieceType}
import chess.model.rules.BitboardAttacks

/** Static Exchange Evaluator (SEE).
  *
  * Given a capture move `from→to`, computes the net material won
  * (in centipawns from the initiator's POV) assuming both sides
  * recapture with their cheapest remaining attacker until one side
  * has no attackers left or refuses to continue. The classic
  * "swap algorithm" — equivalent to playing out the exchange
  * tree to its forced static conclusion without searching anything.
  *
  * Why this matters for move ordering: pure MVV-LVA can't tell a
  * winning queen capture from a queen sacrifice. A rook capturing
  * a defended bishop looks like +325 cp on the MVV-LVA scale but is
  * actually −175 (rook traded for bishop+pawn). SEE catches that
  * without doing a real recursive search, so it's the cheapest way
  * to rank captures correctly.
  *
  * Includes X-ray detection: when a slider captures and clears the
  * way for a slider behind it (e.g., queen behind bishop), the
  * trailing slider becomes the next attacker. Tracked by removing
  * the just-used attacker from the occupancy bitboard before
  * re-querying the slider-attack masks.
  *
  * Lives in [[internal]] so the search module can call it without
  * exposing it on the public engine surface.
  */
private[engine] object StaticExchange:

  /** Kaufman-ish piece values used by SEE. Matches the values in
    * [[chess.bot.engine.AlphaBetaSearch.pieceValue]] so MVV-LVA
    * ordering and SEE pruning use the same material scale. */
  private inline def value(pt: PieceType): Int = pt match
    case PieceType.Pawn   => 100
    case PieceType.Knight => 320
    case PieceType.Bishop => 330
    case PieceType.Rook   => 500
    case PieceType.Queen  => 900
    case PieceType.King   => 20_000

  /** SEE for a capture from `state`. Returns the net centipawn gain
    * from the initiator's POV. Positive = winning capture, 0 =
    * balanced trade, negative = losing capture.
    *
    * Returns 0 if `move` doesn't actually capture anything (the
    * caller is expected to pre-filter to captures, but the explicit
    * zero short-circuit makes accidental non-capture calls a no-op
    * rather than nonsense). */
  def see(state: PositionView, move: Int): Int =
    val from = MoveInt.fromIdx(move)
    val to   = MoveInt.toIdx(move)
    val board = state.board

    val victimOpt   = board.get(positionAt(to))
    val attackerOpt = board.get(positionAt(from))
    if victimOpt.isEmpty || attackerOpt.isEmpty then 0
    else
      val initialVictim   = value(victimOpt.get.pieceType)
      val initialAttacker = value(attackerOpt.get.pieceType)
      val initiatorColor  = state.activeColor

      // Local mutable copies of the bitboard state. We "remove" each
      // attacker that participates in the swap from the appropriate
      // per-piece bitboard + the overall occupancy. The target square
      // stays occupied for the whole sequence (someone is always
      // sitting on it after their turn).
      var occ = board.occupancy.raw
      var pawnsW   = board.pawnsW.raw
      var knightsW = board.knightsW.raw
      var bishopsW = board.bishopsW.raw
      var rooksW   = board.rooksW.raw
      var queensW  = board.queensW.raw
      var kingW    = board.kingW.raw
      var pawnsB   = board.pawnsB.raw
      var knightsB = board.knightsB.raw
      var bishopsB = board.bishopsB.raw
      var rooksB   = board.rooksB.raw
      var queensB  = board.queensB.raw
      var kingB    = board.kingB.raw

      // Apply the initial capture's bitboard effects.
      //   - Vacate `from` (our attacker left it). `to` is still set —
      //     our piece replaced the victim. So XOR `from` out of occ.
      //   - Remove the victim from its color/type bitboard.
      //   - Remove our attacker from its color/type bitboard.
      occ ^= (1L << from)
      val attackerPiece = attackerOpt.get
      val victimPiece   = victimOpt.get
      // Erase our attacker's bit from its bitboard so X-rays see past it.
      if attackerPiece.color == Color.White then
        attackerPiece.pieceType match
          case PieceType.Pawn   => pawnsW   ^= (1L << from)
          case PieceType.Knight => knightsW ^= (1L << from)
          case PieceType.Bishop => bishopsW ^= (1L << from)
          case PieceType.Rook   => rooksW   ^= (1L << from)
          case PieceType.Queen  => queensW  ^= (1L << from)
          case PieceType.King   => kingW    ^= (1L << from)
      else
        attackerPiece.pieceType match
          case PieceType.Pawn   => pawnsB   ^= (1L << from)
          case PieceType.Knight => knightsB ^= (1L << from)
          case PieceType.Bishop => bishopsB ^= (1L << from)
          case PieceType.Rook   => rooksB   ^= (1L << from)
          case PieceType.Queen  => queensB  ^= (1L << from)
          case PieceType.King   => kingB    ^= (1L << from)
      // And the victim's bit too.
      if victimPiece.color == Color.White then
        victimPiece.pieceType match
          case PieceType.Pawn   => pawnsW   ^= (1L << to)
          case PieceType.Knight => knightsW ^= (1L << to)
          case PieceType.Bishop => bishopsW ^= (1L << to)
          case PieceType.Rook   => rooksW   ^= (1L << to)
          case PieceType.Queen  => queensW  ^= (1L << to)
          case PieceType.King   => kingW    ^= (1L << to)
      else
        victimPiece.pieceType match
          case PieceType.Pawn   => pawnsB   ^= (1L << to)
          case PieceType.Knight => knightsB ^= (1L << to)
          case PieceType.Bishop => bishopsB ^= (1L << to)
          case PieceType.Rook   => rooksB   ^= (1L << to)
          case PieceType.Queen  => queensB  ^= (1L << to)
          case PieceType.King   => kingB    ^= (1L << to)

      // The standard swap-list algorithm. gain[d] is "material won
      // if the chain stops at depth d". After the chain is built, a
      // backward minimax pass collapses each side's "stop or continue"
      // decisions into the final SEE score in gain[0].
      val gain = new Array[Int](32)
      var d = 0
      gain(0) = initialVictim
      var pieceOnTarget = initialAttacker
      var sideToMove    = opposite(initiatorColor)

      var continuing = true
      while continuing && d < 31 do
        // Find this side's cheapest attacker of `to` given the current
        // (X-ray-aware) occupancy. We pack pieceType ordinal + square
        // into a single Int to avoid an Option/tuple allocation per
        // probe — SEE is on the move-ordering hot path.
        val packed =
          if sideToMove == Color.White then
            cheapestAttacker(
              to, occ,
              pawnsW, knightsW, bishopsW, rooksW, queensW, kingW,
              BitboardAttacks.blackPawnAttackersOf,
            )
          else
            cheapestAttacker(
              to, occ,
              pawnsB, knightsB, bishopsB, rooksB, queensB, kingB,
              BitboardAttacks.whitePawnAttackersOf,
            )
        if packed < 0 then continuing = false
        else
          val ptOrd = packed >>> 6
          val sq    = packed & 0x3f
          d += 1
          gain(d) = pieceOnTarget - gain(d - 1)
          occ ^= (1L << sq)
          // Remove the used attacker from its bitboard for X-ray.
          if sideToMove == Color.White then ptOrd match
            case 0 => kingW    ^= (1L << sq)
            case 1 => queensW  ^= (1L << sq)
            case 2 => rooksW   ^= (1L << sq)
            case 3 => bishopsW ^= (1L << sq)
            case 4 => knightsW ^= (1L << sq)
            case 5 => pawnsW   ^= (1L << sq)
          else ptOrd match
            case 0 => kingB    ^= (1L << sq)
            case 1 => queensB  ^= (1L << sq)
            case 2 => rooksB   ^= (1L << sq)
            case 3 => bishopsB ^= (1L << sq)
            case 4 => knightsB ^= (1L << sq)
            case 5 => pawnsB   ^= (1L << sq)
          pieceOnTarget = valueByOrd(ptOrd)
          sideToMove    = opposite(sideToMove)
      end while

      // Minimax backward fold: each side may refuse to continue if
      // continuing is worse than stopping. The recurrence is
      // `gain[d-1] = -max(-gain[d-1], gain[d])`.
      while d > 0 do
        gain(d - 1) = -math.max(-gain(d - 1), gain(d))
        d -= 1
      gain(0)

  /** Pick the cheapest attacker of `sq` for one side, given the
    * current occupancy. Returns a packed `pieceTypeOrd<<6 | sq`, or
    * `-1` when no attacker. Pawn order: PieceType.King=0..Pawn=5
    * (matching the enum's ordinal order). Probes lowest-value first
    * (pawn), then knight, bishop, rook, queen, king. Sliders share
    * the bishop/rook ray masks — we query them with the same masks
    * the move generator uses, so the X-ray story is "AND queens
    * with bishop-rays and rook-rays separately" rather than a
    * combined call. */
  private inline def cheapestAttacker(
      sq: Int,
      occ: Long,
      pawns: Long, knights: Long, bishops: Long, rooks: Long, queens: Long, king: Long,
      pawnAttackerMask: Array[Long],
  ): Int =
    // Pawn — cheapest, probe first.
    val pawnAtk = pawns & pawnAttackerMask(sq)
    if pawnAtk != 0L then (5 << 6) | java.lang.Long.numberOfTrailingZeros(pawnAtk)
    else
      val knightAtk = knights & BitboardAttacks.knightAttacks(sq)
      if knightAtk != 0L then (4 << 6) | java.lang.Long.numberOfTrailingZeros(knightAtk)
      else
        val bishopRays = BitboardAttacks.bishopAttacks(sq, occ)
        val bishopAtk  = bishops & bishopRays
        if bishopAtk != 0L then (3 << 6) | java.lang.Long.numberOfTrailingZeros(bishopAtk)
        else
          val rookRays = BitboardAttacks.rookAttacks(sq, occ)
          val rookAtk  = rooks & rookRays
          if rookAtk != 0L then (2 << 6) | java.lang.Long.numberOfTrailingZeros(rookAtk)
          else
            // Queens move along bishop OR rook rays.
            val queenAtk = queens & (bishopRays | rookRays)
            if queenAtk != 0L then (1 << 6) | java.lang.Long.numberOfTrailingZeros(queenAtk)
            else
              val kingAtk = king & BitboardAttacks.kingAttacks(sq)
              if kingAtk != 0L then (0 << 6) | java.lang.Long.numberOfTrailingZeros(kingAtk)
              else -1

  /** Inverse of [[PieceType]] ordinal → value, used by the swap
    * loop where we operate on raw Int ordinals to avoid Piece
    * allocations. Keep in sync with [[value]]. */
  private inline def valueByOrd(ord: Int): Int = ord match
    case 0 => 20_000  // King
    case 1 => 900     // Queen
    case 2 => 500     // Rook
    case 3 => 330     // Bishop
    case 4 => 320     // Knight
    case _ => 100     // Pawn

  private inline def opposite(c: Color): Color =
    if c == Color.White then Color.Black else Color.White

  private inline def positionAt(idx: Int): Position =
    Position(('a' + (idx % 8)).toChar, idx / 8 + 1)
