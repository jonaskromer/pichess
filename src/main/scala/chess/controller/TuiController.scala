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
    case Draw
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
    if trimmed.startsWith(loadPrefix) then
      Command.Load(trimmed.drop(loadPrefix.length))
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
        case "draw" => Command.Draw
        case raw    => Command.Move(raw)

  def handleCommand(
      command: Command,
      gs: GameService,
      session: SubscriptionRef[SessionState],
      shutdown: Promise[Nothing, Unit],
      flipped: Boolean
  ): IO[Throwable, Result] =
    // Common error path: surface err.message in the session and continue.
    // Closures capture `session` and `flipped`, so branches only supply the
    // effect to run and optionally how to react to its success.
    def withErrorHandling[A](action: IO[GameError, A])(
        onSuccess: A => IO[Throwable, Any]
    ): IO[Throwable, Result] =
      action.foldZIO(
        err =>
          session
            .update(_.copy(error = Some(err.message), output = None))
            .as(Result.Continue(flipped)),
        a => onSuccess(a).as(Result.Continue(flipped))
      )

    def runAndContinue(action: IO[GameError, Unit]): IO[Throwable, Result] =
      withErrorHandling(action)(_ => ZIO.unit)

    command match
      case Command.Quit =>
        shutdown.succeed(()).as(Result.Shutdown)
      case Command.Help =>
        session
          .update(_.copy(error = None, output = None))
          .as(Result.Continue(flipped))
      case Command.Flip =>
        session
          .update(_.copy(error = None, output = None))
          .as(Result.Continue(!flipped))
      case Command.Undo =>
        runAndContinue(GameController.undo(gs, session))
      case Command.Redo =>
        runAndContinue(GameController.redo(gs, session))
      case Command.Draw =>
        runAndContinue(GameController.claimDraw(gs, session))
      case Command.Move(raw) =>
        runAndContinue(GameController.makeMove(gs, session, raw))
      case Command.Load(raw) =>
        withErrorHandling(gs.loadGame(raw)) { case (event, history) =>
          session.set(
            SessionState(
              GameSnapshot.fromHistory(
                event.gameId,
                event.initialState,
                history.reverse
              )
            )
          )
        }
      case Command.Export(format) =>
        session.get.flatMap { s =>
          val text = format match
            case ExportFormat.Fen =>
              ZIO.succeed(FenSerializer.serialize(s.state))
            case ExportFormat.Json =>
              ZIO.succeed(JsonSerializer.serialize(s.state))
            case ExportFormat.Pgn =>
              SanSerializer
                .deriveMoveLog(s.initialState, s.history)
                .orDie
                .flatMap(log => PgnSerializer.serialize(log, s.state.status))
          text.flatMap(t =>
            session
              .update(_.copy(error = None, output = Some(t)))
              .as(Result.Continue(flipped))
          )
        }
