package chess.bot.engine

import java.nio.{ByteBuffer, ByteOrder}

/** Baked move-ordering priors distilled from Stockfish's best moves — the
  * `best` column of the shared Lichess-eval dataset (built offline by
  * `chess.bot.train.PolicyPriorMain`).
  *
  * A "butterfly" `from → to` table (same LERF square indexing as the search's
  * history heuristic), holding a pre-scaled bonus in `[0, MaxBonus]`. A high
  * value means SF frequently played that from→to — a strong *warm start* for
  * quiet-move ordering at nodes where the runtime history table is still cold
  * (shallow / first visit). The search adds it to the quiet-move score, so it
  * only changes the *order* moves are tried, never the result — pure search
  * efficiency (more β-cutoffs → effective depth).
  *
  * O(1) lookup, no per-node inference — the only kind of policy an α-β engine
  * (millions of nodes) can afford. */
final class PolicyPrior private (private val table: Array[Int]):
  /** Pre-scaled ordering bonus for a quiet move `fromIdx → toIdx` (LERF). */
  inline def bonus(fromIdx: Int, toIdx: Int): Int = table(fromIdx * 64 + toIdx)

object PolicyPrior:

  final val Size = 64 * 64

  /** All-zero prior — the no-op fallback when `/policy-prior.bin` isn't baked
    * in yet (so enabling the flag before the resource exists is harmless). */
  val Empty: PolicyPrior = new PolicyPrior(new Array[Int](Size))

  /** Load the baked table from a classpath resource (None if absent). */
  def loadResource(name: String): Option[PolicyPrior] =
    Option(getClass.getResourceAsStream(name)).map { res =>
      try parse(res.readAllBytes())
      finally res.close()
    }

  /** Parse `Size` little-endian int32 bonuses. */
  def parse(bytes: Array[Byte]): PolicyPrior =
    require(
      bytes.length == Size * 4,
      s"policy-prior size mismatch: got ${bytes.length} bytes, expected ${Size * 4}",
    )
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val t  = new Array[Int](Size)
    var i  = 0
    while i < Size do
      t(i) = bb.getInt
      i += 1
    new PolicyPrior(t)

  /** Wrap a raw `from*64+to` table (builder + tests). */
  def of(table: Array[Int]): PolicyPrior =
    require(table.length == Size, s"policy-prior table must be $Size entries")
    new PolicyPrior(table.clone())

  /** Serialise a `from*64+to` table to the on-disk byte layout. */
  def toBytes(table: Array[Int]): Array[Byte] =
    require(table.length == Size, s"policy-prior table must be $Size entries")
    val bb = ByteBuffer.allocate(Size * 4).order(ByteOrder.LITTLE_ENDIAN)
    var i  = 0
    while i < Size do
      bb.putInt(table(i))
      i += 1
    bb.array()
