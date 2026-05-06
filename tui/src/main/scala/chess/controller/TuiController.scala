package chess.controller

/** Command parser for TUI input. The runtime side of the TUI (stdin loop,
  * REST calls into the gateway, board rendering) is not implemented in this
  * iteration — see `docs/roadmap.md` "TUI to REST" for the planned shape.
  *
  * `parseCommand` is preserved as a pure function so the parsing tests (and
  * future TuiMain) keep working without depending on `gameService`.
  */
object TuiController:

  enum ExportFormat:
    case Fen, Pgn, Json

  enum Command:
    case Quit
    case Help
    case Flip
    case Undo
    case Redo
    case Draw
    case Load(raw: String)
    case Export(format: ExportFormat)
    case Move(raw: String)

    /** Whitespace-only input — re-display the prompt without attempting a move.
      * Without this, an accidental enter would fall through to
      * `Command.Move("")` and surface as "Invalid move".
      */
    case Noop

  enum Result:
    case Shutdown
    case Continue(flipped: Boolean)

  private val loadPrefix = "load "
  private val exportPrefix = "export "

  def parseCommand(input: String): Command =
    val trimmed = input.trim
    if trimmed.isEmpty then Command.Noop
    else if trimmed.startsWith(loadPrefix) then
      Command.Load(trimmed.drop(loadPrefix.length))
    else if trimmed.startsWith(exportPrefix) then
      trimmed.drop(exportPrefix.length) match
        case "fen"  => Command.Export(ExportFormat.Fen)
        case "pgn"  => Command.Export(ExportFormat.Pgn)
        case "json" => Command.Export(ExportFormat.Json)
        case _      => Command.Move(trimmed) // will fail as invalid move
    else
      trimmed match
        case "quit" => Command.Quit
        case "help" => Command.Help
        case "flip" => Command.Flip
        case "undo" => Command.Undo
        case "redo" => Command.Redo
        case "draw" => Command.Draw
        case raw    => Command.Move(raw)
