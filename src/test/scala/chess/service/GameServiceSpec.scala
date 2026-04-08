package chess.service

import chess.model.GameEvent
import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import chess.repository.InMemoryGameRepository
import zio.*
import zio.test.*

object GameServiceSpec extends ZIOSpecDefault:

  private val appLayer: ULayer[GameService] =
    InMemoryGameRepository.layer >>> GameServiceLive.layer

  def spec = suite("GameService")(
    suite("newGame")(
      test("return a GameStarted event with initial state") {
        for event <- GameService.newGame()
        yield assertTrue(
          event.initialState == GameState.initial,
          event.gameId.nonEmpty
        )
      },
      test("persist the initial state so getState returns it") {
        for
          event <- GameService.newGame()
          state <- GameService.getState(event.gameId)
        yield assertTrue(state == Some(GameState.initial))
      }
    ),
    suite("loadGame")(
      test("auto-detect FEN and return parsed state") {
        val fen = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
        for (event, moveLog) <- GameService.loadGame(fen)
        yield assertTrue(
          event.gameId.nonEmpty,
          event.initialState.board.size == 2,
          event.initialState.activeColor == Color.White,
          moveLog.isEmpty
        )
      },
      test("auto-detect PGN and return replayed state with move log") {
        val pgn = "1. e4 e5 2. Nf3 *"
        for (event, moveLog) <- GameService.loadGame(pgn)
        yield assertTrue(
          event.gameId.nonEmpty,
          moveLog.length == 3,
          event.initialState.board(Position('f', 3)) == Piece(
            Color.White,
            PieceType.Knight
          )
        )
      },
      test("auto-detect JSON and return parsed state") {
        val json = """{"board": {"e1": "white king", "e8": "black king"}, "activeColor": "white", "castlingRights": {"whiteKingSide": false, "whiteQueenSide": false, "blackKingSide": false, "blackQueenSide": false}, "enPassantTarget": null, "inCheck": false, "status": "playing"}"""
        for (event, moveLog) <- GameService.loadGame(json)
        yield assertTrue(
          event.gameId.nonEmpty,
          event.initialState.board.size == 2,
          moveLog.isEmpty
        )
      },
      test("persist the loaded state") {
        val fen = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
        for
          (event, _) <- GameService.loadGame(fen)
          state <- GameService.getState(event.gameId)
        yield assertTrue(
          state.isDefined,
          state.get.board.size == 2
        )
      },
      test("fail for completely invalid input") {
        for exit <- GameService.loadGame("not valid anything").exit
        yield assertTrue(exit.isFailure)
      }
    ),
    suite("makeMove")(
      test("return a MoveMade event and updated state on a valid move") {
        for
          started <- GameService.newGame()
          (state, event) <- GameService.makeMove(started.gameId, "e2 e4")
        yield assertTrue(
          event.isInstanceOf[GameEvent.MoveMade],
          state.board.get(Position('e', 4)) == Some(
            Piece(Color.White, PieceType.Pawn)
          )
        )
      },
      test("persist the updated state after a valid move") {
        for
          started <- GameService.newGame()
          _ <- GameService.makeMove(started.gameId, "e2 e4")
          stored <- GameService.getState(started.gameId)
        yield assertTrue(
          stored.get.board.get(Position('e', 4)) == Some(
            Piece(Color.White, PieceType.Pawn)
          )
        )
      },
      test("fail for an illegal move") {
        for
          started <- GameService.newGame()
          exit <- GameService.makeMove(started.gameId, "e2 e5").exit
        yield assertTrue(exit.isFailure)
      },
      test("fail for malformed input") {
        for
          started <- GameService.newGame()
          exit <- GameService.makeMove(started.gameId, "garbage").exit
        yield assertTrue(exit.isFailure)
      },
      test("accept SAN pawn push notation") {
        for
          started <- GameService.newGame()
          (state, _) <- GameService.makeMove(started.gameId, "e4")
        yield assertTrue(
          state.board.get(Position('e', 4)) == Some(
            Piece(Color.White, PieceType.Pawn)
          )
        )
      },
      test("accept SAN knight move notation") {
        for
          started <- GameService.newGame()
          (state, _) <- GameService.makeMove(started.gameId, "Nf3")
        yield assertTrue(
          state.board.get(Position('f', 3)) == Some(
            Piece(Color.White, PieceType.Knight)
          )
        )
      },
      test("accept coordinate notation without separator") {
        for
          started <- GameService.newGame()
          (state, _) <- GameService.makeMove(started.gameId, "e2e4")
        yield assertTrue(
          state.board.get(Position('e', 4)) == Some(
            Piece(Color.White, PieceType.Pawn)
          )
        )
      },
      test("reject castling on initial board (path is blocked)") {
        for
          started <- GameService.newGame()
          exit <- GameService.makeMove(started.gameId, "O-O").exit
        yield assertTrue(exit.isFailure)
      },
      test("fail when the game id does not exist") {
        for exit <- GameService.makeMove("nonexistent", "e2 e4").exit
        yield assertTrue(exit.isFailure)
      }
    ),
    suite("getState")(
      test("return None for an unknown game id") {
        for state <- GameService.getState("unknown")
        yield assertTrue(state.isEmpty)
      }
    )
  ).provide(appLayer)
