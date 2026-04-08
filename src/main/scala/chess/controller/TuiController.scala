package chess.controller

import chess.codec.{FenSerializer, JsonSerializer, PgnSerializer}
import chess.model.SessionState
import chess.service.GameService
import zio.*
import zio.stream.SubscriptionRef

object TuiController:

  enum ExportFormat:
    case Fen, Pgn, Json

  enum Command:
    case Quit
    case Help
    case Flip
    case Load(raw: String)
    case Export(format: ExportFormat)
    case Move(raw: String)

  enum Result:
    case Shutdown
    case Continue(flipped: Boolean)

  private val loadPrefix = "load "
  private val exportPrefix = "export "

  def parseCommand(input: String): Command =
    val trimmed = input.trim
    if trimmed.startsWith(loadPrefix) then Command.Load(trimmed.drop(loadPrefix.length))
    else if trimmed.startsWith(exportPrefix) then
      trimmed.drop(exportPrefix.length) match
        case "fen"  => Command.Export(ExportFormat.Fen)
        case "pgn"  => Command.Export(ExportFormat.Pgn)
        case "json" => Command.Export(ExportFormat.Json)
        case other  => Command.Move(trimmed) // will fail as invalid move
    else
      trimmed match
        case "quit" => Command.Quit
        case "help" => Command.Help
        case "flip" => Command.Flip
        case raw    => Command.Move(raw)

  def handleCommand(
      command: Command,
      gs: GameService,
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit],
      flipped: Boolean
  ): IO[Throwable, Result] =
    command match
      case Command.Quit =>
        shutdown.succeed(()).as(Result.Shutdown)
      case Command.Help =>
        session.update(_.copy(error = None, output = None)).as(Result.Continue(flipped))
      case Command.Flip =>
        session.update(_.copy(error = None, output = None)).as(Result.Continue(!flipped))
      case Command.Load(raw) =>
        gs.loadGame(raw)
          .foldZIO(
            err =>
              session
                .update(_.copy(error = Some(err.message)))
                .as(Result.Continue(flipped)),
            { case (event, moveLog) =>
              session
                .set(SessionState(event.gameId, event.initialState, moveLog, None))
                .as(Result.Continue(flipped))
            }
          )
      case Command.Export(format) =>
        session.get.flatMap { s =>
          val text = format match
            case ExportFormat.Fen  => FenSerializer.serialize(s.state)
            case ExportFormat.Pgn  => PgnSerializer.serialize(s.moveLog, s.state.status)
            case ExportFormat.Json => JsonSerializer.serialize(s.state)
          session.update(_.copy(error = None, output = Some(text))).as(Result.Continue(flipped))
        }
      case Command.Move(raw) =>
        GameController
          .makeMove(gs, session, raw)
          .foldZIO(
            err =>
              session
                .update(_.copy(error = Some(err.message)))
                .as(Result.Continue(flipped)),
            _ => ZIO.succeed(Result.Continue(flipped))
          )
