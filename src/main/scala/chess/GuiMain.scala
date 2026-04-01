package chess

import chess.model.GameId
import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import chess.repository.InMemoryGameRepository
import chess.service.GameService
import chess.view.GuiInteractionState
import scalafx.application.JFXApp3
import scalafx.application.Platform
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Scene
import scalafx.scene.control.{Button, Label, ScrollPane}
import scalafx.scene.layout.{BorderPane, GridPane, HBox}
import scalafx.scene.text.Font
import scalafx.Includes.*
import zio.*
import scala.compiletime.uninitialized

object GuiMain extends JFXApp3:

  // We need a custom runtime to execute ZIO effects from JavaFX callbacks
  private implicit val unsafe: Unsafe = Unsafe.unsafe(identity)
  private val runtime = Runtime.default

  // Service references and game state
  private var gameService: GameService = uninitialized
  private var currentGameId: GameId = uninitialized
  private var moveHistory: List[(Color, String)] = Nil

  // JavaFX mutable state variables
  private var guiState: GuiInteractionState = GuiInteractionState.Idle

  private lazy val boardGrid = new GridPane {
    alignment = Pos.Center
    hgap = 1
    vgap = 1
  }

  private lazy val statusLabel = new Label("Starting game...") {
    font = Font(20)
    padding = Insets(15)
  }

  private lazy val logLabel = new Label("") {
    font = Font(16)
    padding = Insets(10)
  }

  override def start(): Unit =
    // Initialize ZIO dependencies synchronously on startup
    val (service, event) = runtime.unsafe
      .run(
        for
          env <- ZIO
            .environment[GameService]
            .provide(GameService.layer, InMemoryGameRepository.layer)
          e <- env.get.newGame()
        yield (env.get, e)
      )
      .getOrThrowFiberFailure()

    gameService = service
    currentGameId = event.gameId

    val rootPane = new BorderPane {
      top = new HBox {
        alignment = Pos.Center
        children = Seq(statusLabel)
      }
      center = boardGrid
      bottom = new ScrollPane {
        content = logLabel
        fitToWidth = true
        prefHeight = 150
      }
    }

    stage = new JFXApp3.PrimaryStage {
      title = "πChess - ScalaFX"
      width = 600
      height = 700
      scene = new Scene(rootPane)
    }

    Platform.runLater {
      renderState(event.initialState)
    }

  private def handleSquareClick(pos: Position, state: GameState): Unit =
    val (nextState, moveOpt) = GuiInteractionState.handleClick(guiState, pos)
    guiState = nextState

    moveOpt match
      case Some(moveStr) =>
        val color = state.activeColor
        val effect = gameService
          .makeMove(currentGameId, moveStr)
          .foldZIO(
            err =>
              ZIO.succeed(Platform.runLater {
                statusLabel.text.value =
                  s"Error: ${err.getClass.getSimpleName} - ${err}" // Simple error display
                renderState(state) // Reset visually
              }),
            result =>
              ZIO.succeed(Platform.runLater {
                val (newState, event) = result
                val sanMove =
                  chess.notation.SanSerializer.toSan(event.move, state)
                moveHistory = moveHistory :+ (color, sanMove)
                renderState(newState)
              })
          )
        runtime.unsafe.run(effect)
      case None =>
        // Just visually update the board to show selection
        renderState(state)

  private def renderState(state: GameState): Unit =
    boardGrid.children.clear()
    statusLabel.text.value = s"${state.activeColor}'s turn"

    val selectedPos = guiState match
      case GuiInteractionState.Selected(p) => Some(p)
      case _                               => None

    // Render ranks 8 down to 1
    for (row <- 8.to(1).by(-1); col <- 'a'.to('h')) do
      val pos = Position(col, row)
      val isLight = (col - 'a' + row) % 2 != 0

      val baseColor = if (isLight) "#f0d9b5" else "#b58863"
      val colorStyle =
        if (selectedPos.contains(pos)) s"-fx-background-color: #f6f669;"
        else s"-fx-background-color: $baseColor;"

      val pieceOpt = state.board.get(pos)
      val unicodeChar = pieceOpt.map(unicodeFor).getOrElse(" ")

      val textColor = pieceOpt.map(_.color) match
        case Some(Color.White) => "white"
        case Some(Color.Black) => "black"
        case None              => "transparent"

      val btn = new Button(unicodeChar.toString) {
        prefWidth = 60
        prefHeight = 60
        minWidth = 60
        minHeight = 60
        maxWidth = 60
        maxHeight = 60
        padding = Insets(0)
        font = Font(36)
        style = s"$colorStyle -fx-text-fill: $textColor;"
        onAction = new javafx.event.EventHandler[javafx.event.ActionEvent]:
          def handle(e: javafx.event.ActionEvent): Unit = handleSquareClick(pos, state)
      }

      boardGrid.add(btn, col - 'a', 8 - row)

    // Update log
    val grouped = moveHistory.grouped(2).zipWithIndex.toList
    val logText = grouped
      .map { case (moves, idx) =>
        val number = idx + 1
        val white = moves.headOption.map(_._2).getOrElse("")
        val black = if (moves.size > 1) moves(1)._2 else ""
        s"$number. $white $black"
      }
      .mkString("\n")
    logLabel.text.value = logText

  private def unicodeFor(piece: Piece): Char =
    import Color.*
    import PieceType.*
    (piece.color, piece.pieceType) match
      case (White, King)   => '♔'
      case (White, Queen)  => '♕'
      case (White, Rook)   => '♖'
      case (White, Bishop) => '♗'
      case (White, Knight) => '♘'
      case (White, Pawn)   => '♙'
      case (Black, King)   => '♚'
      case (Black, Queen)  => '♛'
      case (Black, Rook)   => '♜'
      case (Black, Bishop) => '♝'
      case (Black, Knight) => '♞'
      case (Black, Pawn)   => '♟'
