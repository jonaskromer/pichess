package chess.service

import chess.events.InMemoryGameEventProducer
import chess.persistence.InMemoryGameRepository
import chess.model.GameEvent
import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.*
import zio.test.*

object GameServiceSpec extends ZIOSpecDefault:

  private val appLayer: ULayer[GameService] =
    ZLayer.make[GameService](
      GameServiceLive.layer,
      InMemoryGameRepository.layer,
      InMemoryGameEventProducer.layer
    )

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
        for (event, history) <- GameService.loadGame(fen)
        yield assertTrue(
          event.gameId.nonEmpty,
          event.initialState.board == Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          event.initialState.activeColor == Color.White,
          history.isEmpty
        )
      },
      test("auto-detect PGN and return replayed state with moves") {
        val pgn = "1. e4 e5 2. Nf3 *"
        for (event, history) <- GameService.loadGame(pgn)
        yield
          val currentState = history.last._2
          assertTrue(
            event.gameId.nonEmpty,
            history.length == 3,
            event.initialState == GameState.initial,
            currentState.board(Position('f', 3)) == Piece(
              Color.White,
              PieceType.Knight
            )
          )
      },
      test("auto-detect JSON and return parsed state") {
        val json =
          """{"board": {"e1": {"color":"White","pieceType":"King"}, "e8": {"color":"Black","pieceType":"King"}}, "activeColor": "White", "castlingRights": {"whiteKingSide": false, "whiteQueenSide": false, "blackKingSide": false, "blackQueenSide": false}, "enPassantTarget": null, "inCheck": false, "status": {"Playing":{}}}"""
        for (event, history) <- GameService.loadGame(json)
        yield assertTrue(
          event.gameId.nonEmpty,
          event.initialState.board == Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          event.initialState.activeColor == Color.White,
          history.isEmpty
        )
      },
      test("persist the loaded state") {
        val fen = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
        val expectedBoard = Map(
          Position('e', 1) -> Piece(Color.White, PieceType.King),
          Position('e', 8) -> Piece(Color.Black, PieceType.King)
        )
        for
          (event, _) <- GameService.loadGame(fen)
          state <- GameService.getState(event.gameId)
        yield assertTrue(
          state.isDefined,
          state.get.board == expectedBoard
        )
      },
      test("fail for completely invalid input") {
        for exit <- GameService.loadGame("not valid anything").exit
        yield assertTrue(exit.isFailure)
      }
    ),
    suite("makeMove")(
      test("return a MoveMade event and a pending mutation on a valid move") {
        for
          started <- GameService.newGame()
          (event, mutation) <- GameService.makeMove(started.gameId, "e2 e4")
        yield assertTrue(
          event.gameId == started.gameId,
          event.move == chess.model.board
            .Move(Position('e', 2), Position('e', 4)),
          mutation.state.board.get(Position('e', 4)) == Some(
            Piece(Color.White, PieceType.Pawn)
          ),
          mutation.state.board.get(Position('e', 2)).isEmpty,
          // A real move always changes state, so the mutation should
          // commit (vs. a no-op which would be a value-equal pre/state).
          mutation.changed,
          // Pre-state is what was loaded; state is the post-move
          // value. The two MUST differ structurally for `changed` to
          // be true.
          mutation.pre.board != mutation.state.board,
        )
      },
      test("does NOT persist the new state until commit") {
        for
          started <- GameService.newGame()
          _       <- GameService.makeMove(started.gameId, "e2 e4")
          stored  <- GameService.getState(started.gameId)
        yield assertTrue(
          // Initial board still in store — makeMove built a Mutation but
          // didn't save.
          stored.get.board.get(Position('e', 2)).contains(
            Piece(Color.White, PieceType.Pawn)
          ),
          stored.get.board.get(Position('e', 4)).isEmpty,
        )
      },
      test("commit persists the new state from the mutation") {
        for
          started <- GameService.newGame()
          (_, mutation) <- GameService.makeMove(started.gameId, "e2 e4")
          _      <- GameService.commit(mutation)
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
          (_, mutation) <- GameService.makeMove(started.gameId, "e4")
        yield assertTrue(
          mutation.state.board.get(Position('e', 4)) == Some(
            Piece(Color.White, PieceType.Pawn)
          )
        )
      },
      test("accept SAN knight move notation") {
        for
          started <- GameService.newGame()
          (_, mutation) <- GameService.makeMove(started.gameId, "Nf3")
        yield assertTrue(
          mutation.state.board.get(Position('f', 3)) == Some(
            Piece(Color.White, PieceType.Knight)
          )
        )
      },
      test("accept coordinate notation without separator") {
        for
          started <- GameService.newGame()
          (_, mutation) <- GameService.makeMove(started.gameId, "e2e4")
        yield assertTrue(
          mutation.state.board.get(Position('e', 4)) == Some(
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
    suite("saveState")(
      test("update the persisted state") {
        val custom = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.King)
          ),
          Color.White
        )
        for
          started <- GameService.newGame()
          _ <- GameService.saveState(started.gameId, custom)
          stored <- GameService.getState(started.gameId)
        yield assertTrue(stored == Some(custom))
      }
    ),
    suite("getState")(
      test("return None for an unknown game id") {
        for state <- GameService.getState("unknown")
        yield assertTrue(state.isEmpty)
      }
    )
  ).provide(appLayer)
