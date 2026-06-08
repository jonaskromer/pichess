package chess.bot.engine

import java.util.concurrent.ConcurrentHashMap

import chess.model.board.Move

/** Transposition table — caches search results keyed by Zobrist hash so
  * the same position reached via different move orders is searched once.
  *
  * Three classical entry flavours:
  *   - [[TTKind.Exact]]   — score is the true minimax value (no cutoff)
  *   - [[TTKind.Lower]]   — score is a lower bound; a β-cutoff happened
  *   - [[TTKind.Upper]]   — score is an upper bound; α was never raised
  *
  * The search consults the TT before evaluating a node. On hit, if the
  * stored search depth ≥ the requested depth AND the stored bound is
  * tight enough to settle the current α/β window, the stored score is
  * returned directly. On miss the search runs normally and writes the
  * result back at the end.
  *
  * The default `inMemory` impl is a [[ConcurrentHashMap]] capped at
  * `maxEntries`; on overflow the oldest-inserted half is evicted in a
  * single sweep. This is naive vs the classic two-bucket replacement
  * schemes used by competitive engines, but at a few-megabyte cap it
  * works perfectly well for a starter bot and keeps the code readable.
  */
trait TranspositionTable:
  def get(hash: Long): Option[TranspositionTable.Entry]
  def put(hash: Long, entry: TranspositionTable.Entry): Unit
  def size: Int
  def clear(): Unit
  /** Advance the aging-generation counter. Called once per
    * top-level search so subsequent `put`s carry the new
    * generation and can preferentially overwrite stale entries
    * from prior searches. Default no-op for table implementations
    * that don't age. */
  def bumpGeneration(): Unit = ()
  /** Enable or disable generation-preferred replacement. When ON,
    * a fresh-generation entry beats a stale higher-depth entry on
    * collisions — search at the new root reuses the table cheaply.
    * Default OFF preserves the historical depth-only policy. */
  def setAgingEnabled(enabled: Boolean): Unit = ()

object TranspositionTable:

  enum Kind:
    case Exact, Lower, Upper

  /** A cached search result.
    *
    * `depth` is how many plies were searched below this node to obtain
    * `score`. A new query at depth ≤ this can reuse the score; deeper
    * queries must re-search (since this entry's depth would only be a
    * lower bound on the true value). `bestMove` is the move that
    * produced the stored score — used by the search for move ordering
    * (try the TT move first → bigger β-cutoffs → smaller node count).
    *
    * `generation` tags the entry with the search that produced it.
    * When aging is enabled, a stored entry from an older generation
    * loses to a fresh-generation put even at higher depth — the old
    * entry's score is from a different game position, while the
    * fresh entry is "live" for the current search.
    */
  final case class Entry(
      depth: Int,
      score: Int,
      kind: Kind,
      bestMove: Option[Move],
      generation: Int = 0,
  )

  /** Default [[ConcurrentHashMap]]-backed table.
    *
    * `maxEntries` caps the underlying map size; when exceeded, the
    * eviction sweep drops every entry whose hash mod 2 matches a flag
    * we toggle each sweep, halving the table in O(n). That's coarser
    * than LRU but doesn't need an access-order data structure — the
    * point is to bound memory, not to be optimally clever.
    */
  def inMemory(maxEntries: Int): TranspositionTable =
    new ConcurrentHashMapTable(maxEntries)

  private final class ConcurrentHashMapTable(maxEntries: Int)
      extends TranspositionTable:
    private val table = new ConcurrentHashMap[Long, Entry](maxEntries * 2)
    @volatile private var evictParity: Long = 0L
    @volatile private var generation: Int = 0
    @volatile private var agingEnabled: Boolean = false

    def get(hash: Long): Option[Entry] =
      Option(table.get(hash))

    def put(hash: Long, entry: Entry): Unit =
      // Depth-preferred replacement: a shallower-depth entry MUST
      // NOT overwrite a deeper one. Without this guard, iterative
      // deepening was destructive — depth-1 entries from each
      // iteration's first pass would clobber the depth-N entries
      // left over from prior moves, and the supposedly "warm" TT
      // ended up colder than running at depth N directly. Allowing
      // equal-depth replacement keeps the freshest score when the
      // same position is revisited at the same depth.
      //
      // With aging ON: a fresh-generation entry always wins over a
      // stale-generation one, regardless of depth. Stale entries
      // are from prior searches that scored a different game
      // position; their depth is irrelevant for the current root.
      val tagged = if entry.generation == 0 then entry.copy(generation = generation) else entry
      val existing = table.get(hash)
      val accept =
        if existing == null then true
        else if agingEnabled && tagged.generation != existing.generation then true
        else tagged.depth >= existing.depth
      if accept then
        table.put(hash, tagged)
        if table.size > maxEntries then evict()

    override def bumpGeneration(): Unit =
      generation = (generation + 1) & 0xff

    override def setAgingEnabled(enabled: Boolean): Unit =
      agingEnabled = enabled

    def size: Int = table.size

    def clear(): Unit = table.clear()

    /** Drop every key whose low bit matches the current sweep parity,
      * then flip the parity. Sweeps alternate odd/even hashes so the
      * eviction set rotates and we don't keep dropping the same half.
      */
    private def evict(): Unit =
      val parity = evictParity
      val it = table.keys()
      while it.hasMoreElements do
        val k = it.nextElement()
        if (k & 1L) == parity then table.remove(k)
      evictParity = parity ^ 1L
