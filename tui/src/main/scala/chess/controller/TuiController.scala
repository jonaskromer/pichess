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

  enum LobbyVisibility:
    case Public, Private

  enum Command:
    case Quit
    case Help
    case Flip
    case Undo
    case Redo
    case Draw
    case Forfeit
    case New
    case Load(raw: String)
    case Export(format: ExportFormat)
    case Move(raw: String)
    /** Phase 2: lobby commands. */
    case Host(visibility: LobbyVisibility)
    case Join(code: String)
    case Lobbies
    case LobbyStatus
    case Start
    /** Local game — alias for `new` that lines up with the web-ui's
      * "new game → local game" wording. */
    case Local

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
  private val joinPrefix = "join "
  private val hostPrefix = "host"

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
    else if trimmed.startsWith(joinPrefix) then
      Command.Join(trimmed.drop(joinPrefix.length).trim)
    else if trimmed == hostPrefix then
      // bare `host` = public lobby (matches the web-ui default)
      Command.Host(LobbyVisibility.Public)
    else if trimmed.startsWith(s"$hostPrefix ") then
      trimmed.drop(hostPrefix.length).trim.toLowerCase match
        case "public"  => Command.Host(LobbyVisibility.Public)
        case "private" => Command.Host(LobbyVisibility.Private)
        case _         => Command.Move(trimmed) // surface as invalid move
    else
      trimmed match
        case "quit"    => Command.Quit
        case "help"    => Command.Help
        case "flip"    => Command.Flip
        case "undo"    => Command.Undo
        case "redo"    => Command.Redo
        case "draw"    => Command.Draw
        case "forfeit" => Command.Forfeit
        case "new"     => Command.New
        case "lobbies" => Command.Lobbies
        case "lobby"   => Command.LobbyStatus
        case "start"   => Command.Start
        case "local"   => Command.Local
        case raw       => Command.Move(raw)
