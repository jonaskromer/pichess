package chess.gameservice

import zio.*
import zio.test.*

import chess.model.board.GameState
import chess.model.{GameError, GameSnapshot}

object GameSessionsSpec extends ZIOSpecDefault:

  private def mkSnapshot(id: String): GameSnapshot =
    GameSnapshot.fresh(id, GameState.initial)

  def spec = suite("GameSessions")(
    suite("register")(
      test("registers a fresh snapshot under its gameId") {
        for
          sessions <- gameSessions
          snapshot = mkSnapshot("game-1")
          ref      <- sessions.register(snapshot)
          state    <- ref.get
        yield assertTrue(state.game.gameId == "game-1")
      },
      test("subsequent get returns the same SubscriptionRef") {
        for
          sessions <- gameSessions
          snapshot = mkSnapshot("game-2")
          registered <- sessions.register(snapshot)
          fetched    <- sessions.get("game-2")
        yield assertTrue(registered eq fetched)
      },
      test("registering a different game does not overwrite earlier ones") {
        for
          sessions <- gameSessions
          _        <- sessions.register(mkSnapshot("game-a"))
          _        <- sessions.register(mkSnapshot("game-b"))
          a        <- sessions.get("game-a")
          b        <- sessions.get("game-b")
          stateA   <- a.get
          stateB   <- b.get
        yield assertTrue(
          stateA.game.gameId == "game-a",
          stateB.game.gameId == "game-b"
        )
      }
    ),
    suite("get")(
      test("fails with GameNotFound for an unregistered id") {
        for
          sessions <- gameSessions
          exit     <- sessions.get("never-was").exit
        yield assertTrue(
          exit.causeOption.exists(_.failureOption.contains(
            GameError.GameNotFound("never-was")
          ))
        )
      }
    ),
    suite("layer")(
      test("constructs a working GameSessions instance") {
        // Exercises the static `layer` so it isn't flagged as uncovered.
        // We register + get through the live service to confirm wiring.
        val program = for
          sessions <- ZIO.service[GameSessions]
          _        <- sessions.register(mkSnapshot("from-layer"))
          ref      <- sessions.get("from-layer")
          state    <- ref.get
        yield state.game.gameId
        for result <- program.provide(GameSessions.layer)
        yield assertTrue(result == "from-layer")
      }
    )
  )

  /** Build a fresh `GameSessions` via the layer so each test gets an
    * isolated session map.
    */
  private def gameSessions: ZIO[Any, Nothing, GameSessions] =
    ZIO.service[GameSessions].provide(GameSessions.layer)
