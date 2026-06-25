package chess.service

import zio.*

import chess.bot.engine.{BotConfig, Search}
import chess.model.board.{GameState, Move}
import chess.model.piece.PieceType
import chess.model.{GameError, GameEvent, GameId}

/** Composes [[GameService]] with the bot-engine so a player's move
  * automatically triggers the bot's reply when it's the bot's turn.
  *
  * Sits one layer above the pure GameService — game logic still lives down
  * there. The orchestrator just sequences: "apply the player's move, commit it,
  * check whose turn it is, if it's the bot's turn run the search + apply that
  * move, commit it, return both events together for the caller to ship to the
  * UI". The bot-config repo tracks which games are in vs-bot mode.
  */
trait VsBotOrchestrator:

  /** Create a fresh game in vs-bot mode. If the bot plays white (or the loaded
    * position has the bot to move on the first ply), the orchestrator plays the
    * opening move immediately so the player sees the bot's first reply on the
    * very first state response.
    */
  def newGameVsBot(
      config: BotConfig
  ): IO[GameError, VsBotOrchestrator.GameStart]

  /** Apply the player's move + (if it's now the bot's turn) the bot's reply.
    * Both are committed before the call returns; the result carries both events
    * so the gateway can stream them as a single state update to the UI.
    */
  def makeMove(
      id: GameId,
      raw: String
  ): IO[GameError, VsBotOrchestrator.MoveResult]

object VsBotOrchestrator:

  /** Outcome of [[newGameVsBot]]: the standard GameStarted event, plus the
    * bot's opening move if it had the white side.
    */
  final case class GameStart(
      started: GameEvent.GameStarted,
      botOpening: Option[GameEvent.MoveMade]
  )

  /** Outcome of [[makeMove]]: the player's move event always, the bot's reply
    * when applicable. `botReply` is `None` when:
    *   - the game isn't in vs-bot mode (config lookup miss), or
    *   - the player's move just ended the game (mate/draw/etc.), or
    *   - it's now the player's turn (e.g. bot plays white and the player just
    *     played black's move — bot will react on the player's NEXT move, not
    *     this one).
    */
  final case class MoveResult(
      playerMove: GameEvent.MoveMade,
      botReply: Option[GameEvent.MoveMade]
  )

  def make(
      gameService: GameService,
      configs: BotConfigRepository,
      search: Search
  ): VsBotOrchestrator =
    new Live(gameService, configs, search)

  val layer
      : URLayer[GameService & BotConfigRepository & Search, VsBotOrchestrator] =
    ZLayer.fromFunction(make(_, _, _))

  /** Concrete impl. The trait lives separately so tests + alternate
    * orchestrators (e.g. a future stream-based one) can substitute.
    */
  private final class Live(
      gameService: GameService,
      configs: BotConfigRepository,
      search: Search
  ) extends VsBotOrchestrator:

    def newGameVsBot(config: BotConfig): IO[GameError, GameStart] =
      for
        started <- gameService.newGame()
        _ <- configs.save(started.gameId, config)
        // If the bot has white-to-move at startup, play its opening
        // immediately so the player's first state response carries
        // both the GameStarted AND the bot's move.
        botOpening <-
          if config.botSide == started.initialState.activeColor &&
            !started.initialState.status.isOver
          then
            playBotMove(started.gameId, started.initialState, config).map(
              Some(_)
            )
          else ZIO.succeed(None)
      yield GameStart(started, botOpening)

    def makeMove(id: GameId, raw: String): IO[GameError, MoveResult] =
      for
        // Apply + commit the player's move first; commit-on-failure
        // semantics mirror the existing controller behaviour.
        (playerEvent, playerMutation) <- gameService.makeMove(id, raw)
        _ <- gameService.commit(playerMutation)
        // Decide whether the bot now needs to reply.
        cfg <- configs.get(id)
        botReply <-
          cfg match
            case Some(c)
                if c.botSide == playerEvent.resultingState.activeColor &&
                  !playerEvent.resultingState.status.isOver =>
              playBotMove(id, playerEvent.resultingState, c).map(Some(_))
            case _ => ZIO.succeed(None)
      yield MoveResult(playerEvent, botReply)

    /** Run search at the configured depth, format the chosen move, apply it via
      * the same GameService pathway the player uses, and commit. If the bot's
      * search returns no move (terminal position the rules engine surprised us
      * at — shouldn't happen on a non- terminal state but we guard) we surface
      * that as an [[GameError.InvalidMove]] so the caller can mark the game as
      * resigned / drawn.
      */
    private def playBotMove(
        id: GameId,
        state: GameState,
        config: BotConfig
    ): IO[GameError, GameEvent.MoveMade] =
      for
        moveOpt <- search.bestMove(state, config.difficulty.searchDepth)
        move <- ZIO
          .fromOption(moveOpt)
          .orElseFail(
            GameError.InvalidMove(
              s"Bot search returned no move at non-terminal position in $id"
            )
          )
        uci = toUci(move)
        (event, mutation) <- gameService.makeMove(id, uci)
        _ <- gameService.commit(mutation)
      yield event

    private def toUci(move: Move): String = VsBotOrchestrator.toUci(move)

  /** Render a [[Move]] as UCI ("e2e4", "e7e8q") — same wire format the parser
    * already accepts via its coordinate-notation path. Inlined here (not pulled
    * from bot-lichess) so game-service doesn't depend on bot-lichess.
    *
    * Lifted to the companion object so tests can verify every promotion arm
    * without driving a full vs-bot game flow that happens to land on a
    * back-rank pawn move.
    */
  private[service] def toUci(move: Move): String =
    val base = s"${move.from.col}${move.from.row}${move.to.col}${move.to.row}"
    move.promotion match
      case Some(PieceType.Queen)  => base + "q"
      case Some(PieceType.Rook)   => base + "r"
      case Some(PieceType.Bishop) => base + "b"
      case Some(PieceType.Knight) => base + "n"
      case _                      => base
