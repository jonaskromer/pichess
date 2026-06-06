package chess.bot.engine.internal

import java.nio.ByteBuffer

/** Loads the baked CMH seed table from `/counter-seed.bin` (a
  * 16,384-byte resource emitted by `CounterSeedMain` in the
  * training module).
  *
  * The seed is one `Int` per `(prev_from, prev_to)` key — 64×64
  * cells, big-endian. Each cell holds the modal master-game reply
  * to that opponent move, or `-1` ("no data, leave the slot
  * empty"). At runtime, [[chess.bot.engine.AlphaBetaSearch]]
  * starts each search with this table as its initial CMH state;
  * own β-cutoffs overwrite it as the search runs.
  *
  * Why bake at build time vs scan PGNs on each engine start:
  * scanning the full corpus is multi-minute, while loading a
  * 16 kB binary blob is a millisecond and bit-identical to the
  * scan output. */
private[engine] object CounterMoveSeed:

  /** Number of `(from, to)` slots — 64 squares × 64 squares. */
  final val Size: Int = 4096

  /** Sentinel "no recorded reply" — must match `MoveInt`'s
    * convention for "no killer" so the search's existing `== -1`
    * checks treat un-seeded cells as empty. */
  final val NoReply: Int = -1

  /** Try to load the seed resource. Returns a fresh `Array[Int]`
    * filled with [[NoReply]] when the resource isn't bundled —
    * production engines without the trained seed work fine, they
    * just start the CMH cold like before. */
  def load(): Array[Int] =
    val res = getClass.getResourceAsStream("/counter-seed.bin")
    if res == null then Array.fill(Size)(NoReply)
    else
      try
        val bytes = res.readAllBytes()
        if bytes.length != Size * 4 then Array.fill(Size)(NoReply)
        else
          val bb  = ByteBuffer.wrap(bytes)
          val arr = new Array[Int](Size)
          var i   = 0
          while i < Size do
            arr(i) = bb.getInt
            i += 1
          arr
      finally res.close()
