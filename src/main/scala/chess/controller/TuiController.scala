package chess.controller

import chess.codec.{FenSerializer, JsonSerializer, PgnSerializer}
import chess.model.{GameError, GameSnapshot, SessionState}
import chess.notation.SanSerializer
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
    case Undo
    case Redo
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
        case "undo" => Command.Undo
        case "redo" => Command.Redo
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
      case Command.Undo =>
        GameController
          .undo(gs, session)
          .foldZIO(
            err =>
              session
                .update(_.copy(error = Some(err.message)))
                .as(Result.Continue(flipped)),
            _ => ZIO.succeed(Result.Continue(flipped))
          )
      case Command.Redo =>
        GameController
          .redo(gs, session)
          .foldZIO(
            err =>
              session
                .update(_.copy(error = Some(err.message)))
                .as(Result.Continue(flipped)),
            _ => ZIO.succeed(Result.Continue(flipped))
          )
      case Command.Load(raw) =>
        gs.loadGame(raw)
          .foldZIO(
            err =>
              session
                .update(_.copy(error = Some(err.message)))
                .as(Result.Continue(flipped)),
            { case (event, moves, currentState) =>
              session
                .set(SessionState(
                  GameSnapshot(event.gameId, event.initialState, moves, Nil, currentState)
                ))
                .as(Result.Continue(flipped))
            }
          )
      case Command.Export(format) =>
        session.get.flatMap { s =>
          val text = format match
            case ExportFormat.Fen  => ZIO.succeed(FenSerializer.serialize(s.state))
            case ExportFormat.Json => ZIO.succeed(JsonSerializer.serialize(s.state))
            case ExportFormat.Pgn =>
              SanSerializer
                .deriveMoveLog(s.initialState, s.moves)
                .orDie
                .map(log => PgnSerializer.serialize(log, s.state.status))
          text.flatMap(t =>
            session
              .update(_.copy(error = None, output = Some(t)))
              .as(Result.Continue(flipped))
          )
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
