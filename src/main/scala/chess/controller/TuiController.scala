package chess.controller

import chess.model.SessionState
import chess.service.GameService
import zio.*
import zio.stream.SubscriptionRef

object TuiController:

  enum Command:
    case Quit
    case Help
    case Flip
    case Fen(raw: String)
    case Move(raw: String)

  enum Result:
    case Shutdown
    case Continue(flipped: Boolean)

  private val fenPrefix = "fen "

  def parseCommand(input: String): Command =
    val trimmed = input.trim
    if trimmed.startsWith(fenPrefix) then Command.Fen(trimmed.drop(fenPrefix.length))
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
        session.update(_.copy(error = None)).as(Result.Continue(flipped))
      case Command.Flip =>
        session.update(_.copy(error = None)).as(Result.Continue(!flipped))
      case Command.Fen(raw) =>
        gs.newGameFromFen(raw)
          .foldZIO(
            err =>
              session
                .update(_.copy(error = Some(err.message)))
                .as(Result.Continue(flipped)),
            event =>
              session
                .set(SessionState(event.gameId, event.initialState, Nil, None))
                .as(Result.Continue(flipped))
          )
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
