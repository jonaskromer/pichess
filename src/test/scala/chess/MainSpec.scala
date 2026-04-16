package chess

import chess.model.{GameSnapshot, SessionState}
import chess.repository.InMemoryGameRepository
import chess.service.{GameService, GameServiceLive}
import zio.*
import zio.stream.SubscriptionRef
import zio.test.*
import zio.test.TestAspect.*

object MainSpec extends ZIOSpecDefault:

  private val gameLayer: ULayer[GameService] =
    InMemoryGameRepository.layer >>> GameServiceLive.layer

  private def driveApp(
      input: String*
  ): ZIO[GameService, Throwable, Vector[String]] =
    for
      _ <- TestConsole.feedLines(input*)
      _ <- Main.app(headless = true)
      out <- TestConsole.output
    yield out

  def spec = suite("Main")(
    suite("app (headless)")(
      test("'quit' shuts the app down and prints Goodbye!") {
        for out <- driveApp("quit")
        yield assertTrue(out.exists(_.contains("Goodbye!")))
      },
      test("'help' renders the help screen") {
        for out <- driveApp("help", "quit")
        yield assertTrue(out.exists(_.contains("COMMANDS")))
      },
      test("'flip' continues with the board flipped") {
        for out <- driveApp("flip", "quit")
        yield assertTrue(out.exists(_.contains("Goodbye!")))
      },
      test("an invalid move surfaces as an Error: line") {
        for out <- driveApp("e2 e5", "quit")
        yield assertTrue(out.exists(_.contains("Error:")))
      },
      test("a valid move appears in the move log") {
        for out <- driveApp("e2 e4", "quit")
        yield assertTrue(out.exists(_.contains("e4")))
      },
      test("'export fen' surfaces the FEN as output") {
        for out <- driveApp("export fen", "quit")
        yield assertTrue(
          out.exists(_.contains("rnbqkbnr/pppppppp"))
        )
      }
    ).provide(gameLayer) @@ withLiveClock @@ timeout(30.seconds),
    suite("runWith")(
      test(
        "external session change re-renders the TUI without user input"
      ) {
        def waitForOutput(pattern: String): UIO[Unit] =
          TestConsole.output.flatMap { out =>
            if out.exists(_.contains(pattern)) then ZIO.unit
            else ZIO.sleep(20.millis) *> waitForOutput(pattern)
          }

        for
          gs <- ZIO.service[GameService]
          event <- gs.newGame()
          session <- SubscriptionRef.make(
            SessionState(GameSnapshot.fresh(event.gameId, event.initialState))
          )
          shutdown <- Promise.make[Nothing, Unit]
          // The race in tuiLoop subscribes to session.changes only after the
          // first render. Keep nudging the session until we observe the
          // re-render in TestConsole — that proves both the subscription was
          // established and the ExternalChange branch fired.
          nudgeUntilSeen =
            def loop(n: Int): UIO[Unit] =
              TestConsole.output.flatMap { out =>
                if out.exists(_.contains("external touch")) then ZIO.unit
                else
                  session.update(
                    _.copy(output = Some(s"external touch $n"))
                  ) *> ZIO.sleep(20.millis) *> loop(n + 1)
              }
            loop(0)
          mutator =
            waitForOutput("turn — enter") *>
              nudgeUntilSeen *>
              shutdown.succeed(()).unit
          // Run the app and the mutator in parallel within the same test
          // scope so the SubscriptionRef, Promise and TestConsole are shared.
          _ <- Main.runWith(
            session,
            shutdown,
            headless = true,
            port = 0,
            onReady = _ => ZIO.unit
          ) <&> mutator
          out <- TestConsole.output
        yield assertTrue(
          out.exists(_.contains("external touch")),
          out.exists(_.contains("Goodbye!"))
        )
      }
    ).provide(gameLayer) @@ withLiveClock @@ timeout(30.seconds),
    suite("browserCommandFor")(
      test("macOS uses 'open'") {
        assertTrue(
          Main.browserCommandFor("Mac OS X", 8090) ==
            List("open", "http://localhost:8090")
        )
      },
      test("Windows uses 'cmd /c start'") {
        assertTrue(
          Main.browserCommandFor("Windows 11", 8090) ==
            List("cmd", "/c", "start", "http://localhost:8090")
        )
      },
      test("Linux uses 'xdg-open'") {
        assertTrue(
          Main.browserCommandFor("Linux", 8090) ==
            List("xdg-open", "http://localhost:8090")
        )
      },
      test("port is reflected in the URL") {
        assertTrue(
          Main.browserCommandFor("Mac OS X", 12345) ==
            List("open", "http://localhost:12345")
        )
      }
    ),
    suite("openBrowser")(
      test("hands the OS-specific command to the runner") {
        for
          captured <- Ref.make[List[List[String]]](Nil)
          _ <- TestSystem.putProperty("os.name", "Mac OS X")
          _ <- Main.openBrowser(
            8090,
            cmd => captured.update(_ :+ cmd)
          )
          seen <- captured.get
        yield assertTrue(
          seen == List(List("open", "http://localhost:8090"))
        )
      },
      test("falls back to xdg-open when the OS property is missing") {
        for
          captured <- Ref.make[List[List[String]]](Nil)
          // No TestSystem.putProperty — defaults to ""
          _ <- Main.openBrowser(
            9000,
            cmd => captured.update(_ :+ cmd)
          )
          seen <- captured.get
        yield assertTrue(
          seen == List(List("xdg-open", "http://localhost:9000"))
        )
      }
    ),
    suite("runCommand")(
      test("executes the given shell command") {
        // `true` is a no-op command available on every Unix and exits 0.
        Main.runCommand(List("true")).as(assertCompletes)
      } @@ withLiveClock
    ),
    suite("run")(
      test("dispatches to app and shuts down on quit") {
        for
          _ <- TestConsole.feedLines("quit")
          _ <- Main.run.provide(ZLayer.succeed(ZIOAppArgs(Chunk.empty)))
          out <- TestConsole.output
        yield assertTrue(out.exists(_.contains("Goodbye!")))
      } @@ withLiveClock @@ timeout(30.seconds),
      test("respects --headless flag") {
        for
          _ <- TestConsole.feedLines("quit")
          _ <- Main.run.provide(
            ZLayer.succeed(ZIOAppArgs(Chunk("--headless")))
          )
          out <- TestConsole.output
        yield assertTrue(out.exists(_.contains("Goodbye!")))
      } @@ withLiveClock @@ timeout(30.seconds)
    )
  )
