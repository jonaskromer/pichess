package chess.bot.engine

import java.util.concurrent.atomic.{AtomicInteger, AtomicReferenceArray}

import chess.model.board.Move

/** Transposition table — caches search results keyed by Zobrist hash so the
  * same position reached via different move orders is searched once.
  *
  * Three classical entry flavours:
  *   - [[TTKind.Exact]] — score is the true minimax value (no cutoff)
  *   - [[TTKind.Lower]] — score is a lower bound; a β-cutoff happened
  *   - [[TTKind.Upper]] — score is an upper bound; α was never raised
  *
  * The search consults the TT before evaluating a node. On hit, if the stored
  * search depth ≥ the requested depth AND the stored bound is tight enough to
  * settle the current α/β window, the stored score is returned directly. On
  * miss the search runs normally and writes the result back at the end.
  *
  * The default `inMemory` impl is a fixed-size, power-of-two direct-mapped
  * table (slot = `hash & mask`) over an [[AtomicReferenceArray]]. Each slot
  * pairs the full Zobrist key with its entry, so a lookup is one atomic read
  * plus a key compare — no per-probe key boxing (the old
  * `ConcurrentHashMap[Long, Entry]` autoboxed the `Long` key on every get/put)
  * and no torn reads under the parallel (LazySMP) search. Collisions replace
  * under the same depth-preferred / aging policy as a same-key store; memory is
  * bounded by the fixed slot count, so there is no eviction sweep.
  */
trait TranspositionTable:
  def get(hash: Long): Option[TranspositionTable.Entry]
  def put(hash: Long, entry: TranspositionTable.Entry): Unit
  def size: Int
  def clear(): Unit

  /** Advance the aging-generation counter. Called once per top-level search so
    * subsequent `put`s carry the new generation and can preferentially
    * overwrite stale entries from prior searches. Default no-op for table
    * implementations that don't age.
    */
  def bumpGeneration(): Unit = ()

  /** Enable or disable generation-preferred replacement. When ON, a
    * fresh-generation entry beats a stale higher-depth entry on collisions —
    * search at the new root reuses the table cheaply. Default OFF preserves the
    * historical depth-only policy.
    */
  def setAgingEnabled(enabled: Boolean): Unit = ()

object TranspositionTable:

  enum Kind:
    case Exact, Lower, Upper

  /** A cached search result.
    *
    * `depth` is how many plies were searched below this node to obtain `score`.
    * A new query at depth ≤ this can reuse the score; deeper queries must
    * re-search (since this entry's depth would only be a lower bound on the
    * true value). `bestMove` is the move that produced the stored score — used
    * by the search for move ordering (try the TT move first → bigger β-cutoffs
    * → smaller node count).
    *
    * `generation` tags the entry with the search that produced it. When aging
    * is enabled, a stored entry from an older generation loses to a
    * fresh-generation put even at higher depth — the old entry's score is from
    * a different game position, while the fresh entry is "live" for the current
    * search.
    */
  final case class Entry(
      depth: Int,
      score: Int,
      kind: Kind,
      bestMove: Option[Move],
      generation: Int = 0
  )

  /** Default fixed-size direct-mapped table.
    *
    * `maxEntries` is rounded up to the next power of two to give the slot
    * count; the index is `hash & (slots - 1)`. Memory is bounded by that slot
    * count — there is no eviction sweep; a store into an occupied slot (same
    * key or a colliding different key) replaces under the depth-preferred /
    * aging policy below.
    */
  def inMemory(maxEntries: Int): TranspositionTable =
    new ArrayTable(maxEntries)

  /** One occupied slot: the key it was stored under, plus its entry. The key
    * lives here (not in [[Entry]]) so `Entry` equality stays key-agnostic for
    * callers/tests and the search's `Entry(...)` construction needs no change.
    * Immutable, so an [[AtomicReferenceArray]] read never sees a torn (key,
    * entry) pair under the parallel search.
    */
  private final class Slot(val key: Long, val entry: Entry)

  private final class ArrayTable(maxEntries: Int) extends TranspositionTable:
    private val slotCount: Int =
      var c = 1
      while c < math.max(1, maxEntries) do c <<= 1
      c
    private val mask = slotCount - 1
    private val slots = new AtomicReferenceArray[Slot](slotCount)
    private val count = new AtomicInteger(0)
    @volatile private var generation: Int = 0
    @volatile private var agingEnabled: Boolean = false

    def get(hash: Long): Option[Entry] =
      val s = slots.get((hash & mask).toInt)
      // Key compare guards against a slot collision returning another
      // position's entry — a direct-mapped table maps many hashes to
      // one slot, so this verification is what makes lookups sound.
      if s != null && s.key == hash then Some(s.entry) else None

    def put(hash: Long, entry: Entry): Unit =
      // Stamp the current generation when aging is on and the caller
      // didn't pre-tag — one `.copy()` only on that path; the aging-off
      // path stays allocation-equal to the historical put.
      val tagged =
        if agingEnabled && entry.generation == 0 then
          entry.copy(generation = generation)
        else entry
      val idx = (hash & mask).toInt
      val existing = slots.get(idx)
      // Replacement policy — identical for a same-key refresh and a
      // different-key collision. A fresh-generation entry always wins
      // (aging ON); otherwise keep the deeper search (depth-preferred),
      // so a shallow probe can't evict a valuable deep entry. Without
      // depth-preference, iterative deepening was self-defeating: each
      // iteration's depth-1 first pass clobbered the deep entries left
      // by prior moves.
      val accept =
        if existing == null then true
        else if agingEnabled && tagged.generation != existing.entry.generation
        then true
        else tagged.depth >= existing.entry.depth
      if accept then
        slots.set(idx, new Slot(hash, tagged))
        if existing == null then count.incrementAndGet()

    override def bumpGeneration(): Unit =
      generation = (generation + 1) & 0xff

    override def setAgingEnabled(enabled: Boolean): Unit =
      agingEnabled = enabled

    def size: Int = count.get()

    def clear(): Unit =
      var i = 0
      while i < slotCount do
        slots.set(i, null)
        i += 1
      count.set(0)
