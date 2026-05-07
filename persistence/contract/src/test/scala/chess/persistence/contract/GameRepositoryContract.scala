package chess.persistence.contract

import chess.model.board.GameState
import chess.model.piece.Color
import chess.persistence.GameRepository
import zio.*
import zio.test.*

/** Contract every `GameRepository` impl must satisfy. Concrete subclasses
  * provide a `repoLayer` — typically a Testcontainers-backed real DB —
  * and the suite asserts the universal save/load/delete semantics.
  *
  * Backends that fail the contract are, by definition, not drop-in
  * replacements: they violate an invariant some other part of the system
  * relies on.
  */
abstract class GameRepositoryContract extends ZIOSpecDefault:

  /** Backend-specific layer: e.g. `PostgresContainer.layer >>>
    * PostgresGameRepository.layer`.
    */
  def repoLayer: ZLayer[Any, Throwable, GameRepository]

  /** Human-readable label used to namespace test names so concurrent
    * runs of multiple backends produce readable reports.
    */
  def label: String

  override final def spec =
    suite(s"GameRepository contract — $label")(
      test("load returns None for an unknown id") {
        for result <- GameRepository.load("does-not-exist")
        yield assertTrue(result.isEmpty)
      },
      test("save then load returns the same state") {
        val state = GameState.initial
        for
          _      <- GameRepository.save("contract-1", state)
          result <- GameRepository.load("contract-1")
        yield assertTrue(result.contains(state))
      },
      test("save is idempotent (last write wins)") {
        val s1 = GameState.initial
        val s2 = s1.copy(activeColor = Color.Black)
        for
          _      <- GameRepository.save("contract-2", s1)
          _      <- GameRepository.save("contract-2", s2)
          result <- GameRepository.load("contract-2")
        yield assertTrue(result.contains(s2))
      },
      test("delete removes the state") {
        for
          _      <- GameRepository.save("contract-3", GameState.initial)
          _      <- GameRepository.delete("contract-3")
          result <- GameRepository.load("contract-3")
        yield assertTrue(result.isEmpty)
      },
      test("delete on missing id is a no-op (does not fail)") {
        for _ <- GameRepository.delete("never-existed")
        yield assertCompletes
      },
      test("ids are isolated") {
        for
          _      <- GameRepository.save("contract-4-a", GameState.initial)
          missingResult <- GameRepository.load("contract-4-b")
        yield assertTrue(missingResult.isEmpty)
      },
      test("concurrent saves to the same id all complete and one wins") {
        // Stronger test: the impl mustn't deadlock and the final state must
        // be one of the saved values (last-write-wins, exact winner is
        // implementation-defined).
        val states = (0 until 20).map(i =>
          GameState.initial.copy(halfmoveClock = i)
        )
        for
          _ <- ZIO.foreachParDiscard(states)(s =>
                 GameRepository.save("contract-5", s)
               )
          result <- GameRepository.load("contract-5")
        yield assertTrue(
          result.exists(s => states.exists(_ == s))
        )
      }
    ).provideShared(repoLayer)
