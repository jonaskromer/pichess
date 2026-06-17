package chess.bot.tournament

import zio.json.*
import zio.test.*

import chess.model.piece.Color

/** Codifies the NowChess wire format against the exact JSON the server emits
  * (verified against `tournament-server`'s `http/codec/JsonCodecs.scala`).
  * These literal-decode tests are the contract: if the server changes a field
  * name, discriminator, or shape, one of these breaks.
  */
object TournamentEventCodecSpec extends ZIOSpecDefault:

  private val fen =
    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

  def spec = suite("NowChess JSON contract")(
    suite("TournamentEvent (tournament stream)")(
      test("tournamentStarted (no fields)") {
        assertTrue(
          """{"type":"tournamentStarted"}""".fromJson[TournamentEvent] ==
            Right(TournamentEvent.TournamentStarted)
        )
      },
      test("roundStarted") {
        assertTrue(
          """{"type":"roundStarted","round":2}""".fromJson[TournamentEvent] ==
            Right(TournamentEvent.RoundStarted(2))
        )
      },
      test("gameStart carries the colour WE play") {
        assertTrue(
          """{"type":"gameStart","round":1,"gameId":"g1","color":"black"}"""
            .fromJson[TournamentEvent] ==
            Right(TournamentEvent.GameStart(1, "g1", Color.Black))
        )
      },
      test("roundFinished") {
        assertTrue(
          """{"type":"roundFinished","round":3}""".fromJson[TournamentEvent] ==
            Right(TournamentEvent.RoundFinished(3))
        )
      },
      test("tournamentFinished with winner BotRef") {
        assertTrue(
          """{"type":"tournamentFinished","winner":{"id":"bot_x","name":"Foo"}}"""
            .fromJson[TournamentEvent] ==
            Right(TournamentEvent.TournamentFinished(BotRef("bot_x", "Foo")))
        )
      },
      test("an unknown colour is rejected") {
        assertTrue(
          """{"type":"gameStart","round":1,"gameId":"g1","color":"green"}"""
            .fromJson[TournamentEvent]
            .isLeft
        )
      }
    ),
    suite("GameEvent (per-game stream)")(
      test("gameState snapshot (ongoing, no winner)") {
        assertTrue(
          s"""{"type":"gameState","fen":"$fen","moves":"e2e4","turn":"black","clock":{"whiteTime":300.0,"blackTime":299.5},"status":"ongoing","winner":null}"""
            .fromJson[GameEvent] ==
            Right(
              GameEvent.StateSnapshot(
                fen,
                "e2e4",
                Color.Black,
                GameClock(300.0, 299.5),
                "ongoing",
                None
              )
            )
        )
      },
      test("gameState snapshot with a winner colour") {
        assertTrue(
          s"""{"type":"gameState","fen":"$fen","moves":"","turn":"white","clock":{"whiteTime":1.0,"blackTime":2.0},"status":"checkmate","winner":"white"}"""
            .fromJson[GameEvent] ==
            Right(
              GameEvent.StateSnapshot(
                fen,
                "",
                Color.White,
                GameClock(1.0, 2.0),
                "checkmate",
                Some(Color.White)
              )
            )
        )
      },
      test("move (no status field; turn/fen are post-move)") {
        assertTrue(
          s"""{"type":"move","uci":"e2e4","fen":"$fen","turn":"black","clock":{"whiteTime":300.0,"blackTime":300.0}}"""
            .fromJson[GameEvent] ==
            Right(
              GameEvent
                .MovePlayed("e2e4", fen, Color.Black, GameClock(300.0, 300.0))
            )
        )
      },
      test("gameEnd with a winner") {
        assertTrue(
          """{"type":"gameEnd","winner":"white","status":"checkmate"}"""
            .fromJson[GameEvent] ==
            Right(GameEvent.GameEnded(Some(Color.White), "checkmate"))
        )
      },
      test("gameEnd draw (null winner)") {
        assertTrue(
          """{"type":"gameEnd","winner":null,"status":"draw"}"""
            .fromJson[GameEvent] ==
            Right(GameEvent.GameEnded(None, "draw"))
        )
      }
    ),
    suite("REST payloads")(
      test("RegisterResult") {
        assertTrue(
          """{"id":"bot_x","token":"eyJ..."}"""
            .fromJson[TournamentApiClient.RegisterResult] ==
            Right(TournamentApiClient.RegisterResult("bot_x", "eyJ..."))
        )
      },
      test("TournamentInfo ignores the many other tournament fields") {
        // Exactly the flattened object GET /api/tournament/{id} returns.
        val big =
          """{"id":"t1","fullName":"Friday Bots","clock":{"limit":300,"increment":3},
             |"variant":{"key":"standard","name":"Standard"},"rated":true,"nbPlayers":2,
             |"nbRounds":3,"format":"swiss","matchesPerPairing":1,"startPosition":"standard",
             |"createdBy":"u1","status":"started","round":1,"standing":{"page":1,"players":[]},
             |"winner":null}""".stripMargin
        assertTrue(
          big.fromJson[TournamentApiClient.TournamentInfo] ==
            Right(
              TournamentApiClient.TournamentInfo("t1", TournamentClock(300, 3))
            )
        )
      },
      test("round-trip encode/decode of every event survives") {
        val events: List[GameEvent] = List(
          GameEvent.StateSnapshot(
            fen,
            "e2e4",
            Color.Black,
            GameClock(10.0, 9.0),
            "ongoing",
            None
          ),
          GameEvent.MovePlayed("e7e5", fen, Color.White, GameClock(10.0, 9.0)),
          GameEvent.GameEnded(Some(Color.Black), "resigned")
        )
        assertTrue(events.forall(e => e.toJson.fromJson[GameEvent] == Right(e)))
      }
    )
  )
