package chess.bot.engine

import zio.test.*

import chess.model.board.{Move, Position}
import chess.model.piece.PieceType
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
    test("eviction kicks in above the configured cap") {
      val tt = TranspositionTable.inMemory(maxEntries = 4)
      // Insert 10 distinct entries (5 even-keyed, 5 odd-keyed) — once
      // we cross the cap, the eviction sweep halves the survivors by
      // parity, so the final size must stay ≤ maxEntries.
      (1L to 10L).foreach(h => tt.put(h, e1))
      assertTrue(tt.size <= 4)
    },
    test("eviction alternates parity so neither half is permanently lost") {
      // Cap of 10 so a sweep leaves ~5 survivors of one parity, then
      // re-fills with both parities before the next sweep — that's the
      // pattern we actually want to verify (alternating evictions, not
      // catastrophic shrinking at a 2-entry cap).
      val tt = TranspositionTable.inMemory(maxEntries = 10)
      (1L to 30L).foreach(h => tt.put(h, e1))
      val survivors = (1L to 30L).filter(h => tt.get(h).isDefined)
      assertTrue(
        survivors.exists(h => (h & 1L) == 0L),
        survivors.exists(h => (h & 1L) == 1L),
      )
    },
  )
