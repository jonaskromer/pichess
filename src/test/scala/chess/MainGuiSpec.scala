package chess

import chess.model.{GameSnapshot, SessionState}
import chess.repository.InMemoryGameRepository
import chess.service.{GameService, GameServiceLive}
import zio.*
import zio.http.*
import zio.stream.SubscriptionRef
import zio.test.*
import zio.test.TestAspect.*

object MainGuiSpec extends ZIOSpecDefault:

  private val gameLayer: ULayer[GameService] =
    InMemoryGameRepository.layer >>> GameServiceLive.layer

  private def freshSession =
    for
      gs <- ZIO.service[GameService]
      event <- gs.newGame()
      session <- SubscriptionRef.make(
        SessionState(GameSnapshot(event.gameId, event.initialState))
      )
      shutdown <- Promise.make[Nothing, Unit]
    yield (gs, session, shutdown)

  def spec = suite("Main.startGui")(
    // Reproducer for the GUI-server-lifetime bug introduced by the headless
    // refactor (commit c75a3c2): startGui used to call
    // `.provide(Server.defaultWithPort(8090))` on an inner for-comp that
    // completed immediately after `.fork`, releasing the Server layer and
    // tearing down the bound socket before any browser request could land.
    // Symptom: `curl http://localhost:8090/` → connection refused, even
    // though the TUI was running. Do NOT delete this test.
    test(
      "regression: server stays bound for the lifetime of the surrounding scope"
    ) {
      ZIO.scoped {
        for
          (gs, session, shutdown) <- freshSession
          port <- Main.startGui(
            gs,
            session,
            shutdown,
            port = 0,
            onReady = _ => ZIO.unit
          )
          // Sleep ensures startGui's inner for-comp has long-since completed.
          // The original buggy version had torn down the socket by now.
          _ <- ZIO.sleep(200.millis)
          client <- ZIO.service[Client]
          response <- client.batched(
            Request.get(URL.decode(s"http://localhost:$port/").toOption.get)
          )
          body <- response.body.asString
        yield assertTrue(
          response.status == Status.Ok,
          body.contains("<html")
        )
      }
    },
    test("onReady receives the bound port and is invoked") {
      ZIO.scoped {
        for
          (gs, session, shutdown) <- freshSession
          observed <- Promise.make[Nothing, Int]
          port <- Main.startGui(
            gs,
            session,
            shutdown,
            port = 0,
            onReady = p => observed.succeed(p).unit
          )
          ready <- observed.await
        yield assertTrue(ready == port, port > 0)
      }
    },
    test("server is torn down after the scope closes") {
      for
        port <- ZIO.scoped {
          for
            (gs, session, shutdown) <- freshSession
            p <- Main.startGui(
              gs,
              session,
              shutdown,
              port = 0,
              onReady = _ => ZIO.unit
            )
          yield p
        }
        client <- ZIO.service[Client]
        result <- client
          .batched(
            Request.get(URL.decode(s"http://localhost:$port/").toOption.get)
          )
          .either
      yield assertTrue(result.isLeft)
    }
  ).provide(gameLayer, Client.default) @@ timeout(30.seconds) @@ withLiveClock
