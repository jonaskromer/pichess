package chess.opening

import zio.*
import zio.test.*

import chess.events.GameDomainEvent

object OpeningProjectionSpec extends ZIOSpecDefault:

  /** Test fake that captures every recorded edge instead of writing to
    * Neo4j. Lets us assert what would have been persisted without spinning
    * up a database.
    */
  private final class RecordingTree(ref: Ref[List[(String, String, String)]])
      extends OpeningTree:
    def recordMove(
        beforeFen: String,
        san: String,
        afterFen: String
    ): Task[Unit] =
      ref.update(_ :+ ((beforeFen, san, afterFen)))

  private def make: UIO[(OpeningProjection, Ref[List[(String, String, String)]])] =
    for
      ref <- Ref.make(List.empty[(String, String, String)])
      proj <- OpeningProjection.make(RecordingTree(ref))
    yield (proj, ref)

  private val gameId = "game-1"
  private val fen0 = "fen-initial"
  private val fen1 = "fen-after-e4"
  private val fen2 = "fen-after-e5"

  def spec = suite("OpeningProjection.applyEvent")(
    test("GameStarted seeds the tracker; subsequent MoveMade records an edge") {
      for
        (proj, ref) <- make
        _ <- proj.applyEvent(GameDomainEvent.GameStarted(gameId, fen0, 0L))
        _ <- proj.applyEvent(
               GameDomainEvent.MoveMade(gameId, fen1, "e2-e4", "e4", 1L)
             )
        recorded <- ref.get
      yield assertTrue(recorded == List((fen0, "e4", fen1)))
    },
    test("two consecutive MoveMade events chain through the tracker") {
      for
        (proj, ref) <- make
        _ <- proj.applyEvent(GameDomainEvent.GameStarted(gameId, fen0, 0L))
        _ <- proj.applyEvent(
               GameDomainEvent.MoveMade(gameId, fen1, "e2-e4", "e4", 1L)
             )
        _ <- proj.applyEvent(
               GameDomainEvent.MoveMade(gameId, fen2, "e7-e5", "e5", 2L)
             )
        recorded <- ref.get
      yield assertTrue(
        recorded == List(
          (fen0, "e4", fen1),
          (fen1, "e5", fen2)
        )
      )
    },
    test("GameLoaded also seeds the tracker") {
      for
        (proj, ref) <- make
        _ <- proj.applyEvent(
               GameDomainEvent.GameLoaded(gameId, fen0, fen0, 0, 0L)
             )
        _ <- proj.applyEvent(
               GameDomainEvent.MoveMade(gameId, fen1, "e2-e4", "e4", 1L)
             )
        recorded <- ref.get
      yield assertTrue(recorded == List((fen0, "e4", fen1)))
    },
    test("MoveMade without a prior seed records nothing but still tracks state") {
      for
        (proj, ref) <- make
        // First MoveMade arrives with no GameStarted seed: edge cannot be
        // recorded (we don't know the BEFORE FEN), but the FEN is still
        // remembered so the next MoveMade can chain.
        _ <- proj.applyEvent(
               GameDomainEvent.MoveMade(gameId, fen1, "?", "?", 1L)
             )
        afterFirst <- ref.get
        _ <- proj.applyEvent(
               GameDomainEvent.MoveMade(gameId, fen2, "e7-e5", "e5", 2L)
             )
        afterSecond <- ref.get
      yield assertTrue(
        afterFirst.isEmpty,
        afterSecond == List((fen1, "e5", fen2))
      )
    },
    test("GameEnded clears the tracker for the game") {
      for
        (proj, ref) <- make
        _ <- proj.applyEvent(GameDomainEvent.GameStarted(gameId, fen0, 0L))
        _ <- proj.applyEvent(
               GameDomainEvent.GameEnded(gameId, fen1, "Checkmate(White)", 1L)
             )
        // After GameEnded, a stray MoveMade should NOT chain to the prior
        // FEN — tracker is cleared. It just remembers the new FEN as a
        // fresh starting point.
        _ <- proj.applyEvent(
               GameDomainEvent.MoveMade(gameId, fen2, "?", "?", 2L)
             )
        recorded <- ref.get
      yield assertTrue(recorded.isEmpty)
    },
    test("Forfeited clears the tracker") {
      for
        (proj, ref) <- make
        _ <- proj.applyEvent(GameDomainEvent.GameStarted(gameId, fen0, 0L))
        _ <- proj.applyEvent(
               GameDomainEvent.Forfeited(gameId, fen1, "Black", 1L)
             )
        _ <- proj.applyEvent(
               GameDomainEvent.MoveMade(gameId, fen2, "?", "?", 2L)
             )
        recorded <- ref.get
      yield assertTrue(recorded.isEmpty)
    },
    test("DrawClaimed clears the tracker") {
      for
        (proj, ref) <- make
        _ <- proj.applyEvent(GameDomainEvent.GameStarted(gameId, fen0, 0L))
        _ <- proj.applyEvent(
               GameDomainEvent.DrawClaimed(gameId, fen1, "FiftyMoveRule", 1L)
             )
        _ <- proj.applyEvent(
               GameDomainEvent.MoveMade(gameId, fen2, "?", "?", 2L)
             )
        recorded <- ref.get
      yield assertTrue(recorded.isEmpty)
    },
    test("Undone is ignored — opening tree captures only what was played") {
      for
        (proj, ref) <- make
        _ <- proj.applyEvent(GameDomainEvent.GameStarted(gameId, fen0, 0L))
        _ <- proj.applyEvent(
               GameDomainEvent.MoveMade(gameId, fen1, "e2-e4", "e4", 1L)
             )
        // Undone shouldn't record anything new
        _ <- proj.applyEvent(GameDomainEvent.Undone(gameId, fen0, 2L))
        recorded <- ref.get
      yield assertTrue(recorded == List((fen0, "e4", fen1)))
    },
    test("Redone is ignored") {
      for
        (proj, ref) <- make
        _ <- proj.applyEvent(GameDomainEvent.GameStarted(gameId, fen0, 0L))
        _ <- proj.applyEvent(
               GameDomainEvent.MoveMade(gameId, fen1, "e2-e4", "e4", 1L)
             )
        _ <- proj.applyEvent(GameDomainEvent.Redone(gameId, fen1, 2L))
        recorded <- ref.get
      yield assertTrue(recorded == List((fen0, "e4", fen1)))
    },
    test("layer factory wires an OpeningTree into a working projection") {
      // Exercises the OpeningTree -> OpeningProjection ZLayer used by
      // OpeningMain. Provides a no-op tree so the assertion focuses on the
      // wiring, not the recordMove side effect.
      val noopTree: OpeningTree = new OpeningTree:
        def recordMove(beforeFen: String, san: String, afterFen: String) =
          ZIO.unit
      val program = ZIO
        .serviceWithZIO[OpeningProjection](
          _.applyEvent(GameDomainEvent.GameStarted(gameId, fen0, 0L))
        )
        .as(true)
      for ok <- program.provide(
                  ZLayer.succeed(noopTree),
                  OpeningProjection.layer
                )
      yield assertTrue(ok)
    },
    test("multiple games are tracked independently") {
      val gameA = "game-a"
      val gameB = "game-b"
      for
        (proj, ref) <- make
        _ <- proj.applyEvent(GameDomainEvent.GameStarted(gameA, fen0, 0L))
        _ <- proj.applyEvent(GameDomainEvent.GameStarted(gameB, fen0, 0L))
        _ <- proj.applyEvent(
               GameDomainEvent.MoveMade(gameA, fen1, "e2-e4", "e4", 1L)
             )
        _ <- proj.applyEvent(
               GameDomainEvent.MoveMade(gameB, fen2, "d2-d4", "d4", 1L)
             )
        recorded <- ref.get
      yield assertTrue(
        recorded.contains((fen0, "e4", fen1)),
        recorded.contains((fen0, "d4", fen2)),
        recorded.size == 2
      )
    }
  )
