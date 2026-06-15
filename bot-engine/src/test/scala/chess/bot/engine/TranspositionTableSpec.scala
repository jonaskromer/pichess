package chess.bot.engine

import zio.test.*

import chess.model.board.{Move, Position}
import chess.bot.engine.TranspositionTable.{Entry, Kind}

/** Behavioural specs for the in-memory TT. Nothing search-specific —
  * these pin the store / load / cap / clear contract so the search can
  * trust them.
  */
object TranspositionTableSpec extends ZIOSpecDefault:

  private val e1 = Entry(depth = 3, score = 42, kind = Kind.Exact,
                         bestMove = Some(Move(Position('e', 2), Position('e', 4))))
  private val e2 = Entry(depth = 5, score = -7, kind = Kind.Lower,
                         bestMove = None)

  def spec = suite("TranspositionTable")(
    test("get returns None for an unknown hash") {
      val tt = TranspositionTable.inMemory(maxEntries = 8)
      assertTrue(tt.get(0xdeadbeefL).isEmpty, tt.size == 0)
    },
    test("put then get round-trips the entry") {
      val tt = TranspositionTable.inMemory(maxEntries = 8)
      tt.put(1L, e1)
      tt.put(2L, e2)
      assertTrue(
        tt.get(1L).contains(e1),
        tt.get(2L).contains(e2),
        tt.size == 2,
      )
    },
    test("put overwrites the previous entry for the same hash") {
      val tt = TranspositionTable.inMemory(maxEntries = 8)
      tt.put(1L, e1)
      tt.put(1L, e2)
      assertTrue(tt.get(1L).contains(e2), tt.size == 1)
    },
    test("clear empties the table") {
      val tt = TranspositionTable.inMemory(maxEntries = 8)
      tt.put(1L, e1)
      tt.put(2L, e2)
      tt.clear()
      assertTrue(tt.size == 0, tt.get(1L).isEmpty, tt.get(2L).isEmpty)
    },
    // ── Direct-mapped table semantics ──────────────────────────────
    // With maxEntries=8 the table has 8 slots (mask 0x7), so 1 and 9
    // map to the SAME slot but are different positions. The key compare
    // is what stops a collision from returning the wrong entry — the
    // core soundness guard of a direct-mapped TT.
    test("a colliding hash never returns another position's entry") {
      val tt = TranspositionTable.inMemory(maxEntries = 8)
      tt.put(1L, e1)
      assertTrue(
        tt.get(9L).isEmpty,          // 9 collides with 1's slot, key differs
        tt.get(1L).contains(e1),     // the real occupant is intact
      )
    },
    test("a colliding store replaces under the same depth policy, evicting the old key") {
      val tt = TranspositionTable.inMemory(maxEntries = 8)
      tt.put(1L, e1)                 // depth 3
      tt.put(9L, e2)                 // depth 5 ≥ 3 → wins the shared slot
      assertTrue(
        tt.get(9L).contains(e2),
        tt.get(1L).isEmpty,          // 1 was displaced by the collision
      )
    },
    test("depth-preferred: a shallower store does not overwrite a deeper entry") {
      val tt = TranspositionTable.inMemory(maxEntries = 8)
      tt.put(1L, e2)                 // depth 5
      tt.put(1L, e1)                 // depth 3 < 5 → rejected
      assertTrue(tt.get(1L).contains(e2))
    },
    test("aging lets a fresh, shallower entry replace a stale deeper one") {
      val deep    = Entry(depth = 6, score = 1, kind = Kind.Exact, bestMove = None)
      val shallow = Entry(depth = 2, score = 2, kind = Kind.Exact, bestMove = None)
      val tt = TranspositionTable.inMemory(maxEntries = 8)
      tt.setAgingEnabled(true)
      tt.put(1L, deep)               // stamped generation 0
      tt.bumpGeneration()            // generation → 1
      tt.put(1L, shallow)            // fresh gen beats stale despite lower depth
      assertTrue(tt.get(1L).exists(_.depth == 2))
    },
    test("size never exceeds the fixed slot capacity") {
      val tt = TranspositionTable.inMemory(maxEntries = 4) // 4 slots
      (1L to 100L).foreach(h => tt.put(h, e1))
      assertTrue(tt.size <= 4)
    },
  )
