package chess.controller

import zio.test.*

/** Parser-only spec. The TUI runtime (handleCommand → REST gateway calls) is
  * deferred to a future iteration; once landed, expand this spec with mocked
  * sttp responses for each command path.
  */
object TuiControllerSpec extends ZIOSpecDefault:

  def spec = suite("TuiController")(
    suite("parseCommand")(
      test("parse quit") {
        assertTrue(
          TuiController.parseCommand("quit") == TuiController.Command.Quit
        )
      },
      test("parse quit with whitespace") {
        assertTrue(
          TuiController.parseCommand("  quit  ") == TuiController.Command.Quit
        )
      },
      test("parse help") {
        assertTrue(
          TuiController.parseCommand("help") == TuiController.Command.Help
        )
      },
      test("parse flip") {
        assertTrue(
          TuiController.parseCommand("flip") == TuiController.Command.Flip
        )
      },
      test("parse undo") {
        assertTrue(
          TuiController.parseCommand("undo") == TuiController.Command.Undo
        )
      },
      test("parse redo") {
        assertTrue(
          TuiController.parseCommand("redo") == TuiController.Command.Redo
        )
      },
      test("parse draw") {
        assertTrue(
          TuiController.parseCommand("draw") == TuiController.Command.Draw
        )
      },
      test("parse forfeit") {
        assertTrue(
          TuiController.parseCommand("forfeit") == TuiController.Command.Forfeit
        )
      },
      test("parse new") {
        assertTrue(
          TuiController.parseCommand("new") == TuiController.Command.New
        )
      },
      test("parse move input") {
        assertTrue(
          TuiController.parseCommand("e2 e4") == TuiController.Command.Move(
            "e2 e4"
          )
        )
      },
      test("parse SAN input") {
        assertTrue(
          TuiController.parseCommand("Nf3") == TuiController.Command.Move("Nf3")
        )
      },
      test("parse load command") {
        assertTrue(
          TuiController.parseCommand(
            "load rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
          ) == TuiController.Command.Load(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
          )
        )
      },
      test("parse load command with leading whitespace") {
        assertTrue(
          TuiController.parseCommand(
            "  load 4k3/8/8/8/8/8/8/4K3 w - - 0 1"
          ) == TuiController.Command.Load("4k3/8/8/8/8/8/8/4K3 w - - 0 1")
        )
      },
      test("parse export fen") {
        assertTrue(
          TuiController.parseCommand("export fen") ==
            TuiController.Command.Export(TuiController.ExportFormat.Fen)
        )
      },
      test("parse export pgn") {
        assertTrue(
          TuiController.parseCommand("export pgn") ==
            TuiController.Command.Export(TuiController.ExportFormat.Pgn)
        )
      },
      test("parse export json") {
        assertTrue(
          TuiController.parseCommand("export json") ==
            TuiController.Command.Export(TuiController.ExportFormat.Json)
        )
      },
      test("parse export with unknown format falls through to move") {
        assertTrue(
          TuiController.parseCommand("export xyz") ==
            TuiController.Command.Move("export xyz")
        )
      },
      test("parse empty input as Noop (not as an invalid move)") {
        assertTrue(
          TuiController.parseCommand("") == TuiController.Command.Noop
        )
      },
      test("parse whitespace-only input as Noop") {
        assertTrue(
          TuiController.parseCommand("   ") == TuiController.Command.Noop,
          TuiController.parseCommand("\t") == TuiController.Command.Noop
        )
      },
      test("parse host (defaults to public)") {
        assertTrue(
          TuiController.parseCommand("host") ==
            TuiController.Command.Host(TuiController.LobbyVisibility.Public)
        )
      },
      test("parse host public") {
        assertTrue(
          TuiController.parseCommand("host public") ==
            TuiController.Command.Host(TuiController.LobbyVisibility.Public)
        )
      },
      test("parse host private") {
        assertTrue(
          TuiController.parseCommand("host private") ==
            TuiController.Command.Host(TuiController.LobbyVisibility.Private)
        )
      },
      test("parse host with bogus visibility falls through to move") {
        assertTrue(
          TuiController.parseCommand("host xyz") ==
            TuiController.Command.Move("host xyz")
        )
      },
      test("parse join <code> trims and capitalizes nothing — code stored as typed") {
        assertTrue(
          TuiController.parseCommand("join abc123") ==
            TuiController.Command.Join("abc123")
        )
      },
      test("parse lobbies") {
        assertTrue(
          TuiController.parseCommand("lobbies") == TuiController.Command.Lobbies
        )
      },
      test("parse lobby") {
        assertTrue(
          TuiController.parseCommand("lobby") == TuiController.Command.LobbyStatus
        )
      },
      test("parse start") {
        assertTrue(
          TuiController.parseCommand("start") == TuiController.Command.Start
        )
      },
      test("parse local") {
        assertTrue(
          TuiController.parseCommand("local") == TuiController.Command.Local
        )
      },
      test("parse preview <square>") {
        assertTrue(
          TuiController.parseCommand("preview e4") ==
            TuiController.Command.Preview("e4")
        )
      },
      test("parse threats") {
        assertTrue(
          TuiController.parseCommand("threats") == TuiController.Command.Threats
        )
      }
    )
  )
