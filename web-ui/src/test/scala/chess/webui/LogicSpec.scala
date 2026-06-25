package chess.webui

import zio.json.*
import zio.test.*

import chess.api.{
  BoardStateDto,
  GameAnalysisDto,
  GameStatusDto,
  MoveAnalysisDto,
  MoveEntryDto,
  OngoingGame,
  OpeningDto,
  SquareDto
}

object LogicSpec extends ZIOSpecDefault:

  // Minimal state factory — `isPawnPromotion` only inspects `squares`, so
  // the other BoardStateDto fields are placeholders.
  private def stateWith(squares: SquareDto*): BoardStateDto =
    BoardStateDto(
      squares = squares.toList,
      activeColor = "white",
      moveLog = Nil,
      error = None,
      inCheck = false,
      checkedKingPos = None,
      status = GameStatusDto.Playing
    )

  private val whitePawnOnE7 =
    SquareDto("e7", "dark", Some("pawn"), Some("white"))
  private val blackPawnOnE2 =
    SquareDto("e2", "light", Some("pawn"), Some("black"))
  private val whiteRookOnE4 =
    SquareDto("e4", "light", Some("rook"), Some("white"))
  private val emptyE7 =
    SquareDto("e7", "dark", None, None)

  def spec = suite("Logic")(
    suite("isPawnPromotion")(
      test("true when a white pawn moves to rank 8") {
        val s = stateWith(whitePawnOnE7)
        assertTrue(Logic.isPawnPromotion("e7", "e8", s))
      },
      test("true when a black pawn moves to rank 1") {
        val s = stateWith(blackPawnOnE2)
        assertTrue(Logic.isPawnPromotion("e2", "e1", s))
      },
      test("true on a diagonal promotion capture") {
        val s = stateWith(whitePawnOnE7)
        assertTrue(Logic.isPawnPromotion("e7", "d8", s))
      },
      test("false when the moving piece is not a pawn") {
        val s = stateWith(whiteRookOnE4)
        assertTrue(!Logic.isPawnPromotion("e4", "e8", s))
      },
      test("false when the destination rank is not the back rank") {
        val s = stateWith(whitePawnOnE7)
        assertTrue(!Logic.isPawnPromotion("e7", "e6", s))
      },
      test("false when the source square is empty") {
        val s = stateWith(emptyE7)
        assertTrue(!Logic.isPawnPromotion("e7", "e8", s))
      },
      test("false when the source square isn't in the state at all") {
        val s = stateWith()
        assertTrue(!Logic.isPawnPromotion("e7", "e8", s))
      }
    ),
    suite("groupMovesByTwo")(
      test("empty input yields empty output") {
        assertTrue(Logic.groupMovesByTwo(Nil).isEmpty)
      },
      test("single white move yields one row with no black entry") {
        val result = Logic.groupMovesByTwo(
          List(MoveEntryDto("white", "e4"))
        )
        assertTrue(
          result.size == 1,
          result.head == (1, MoveEntryDto("white", "e4"), None)
        )
      },
      test("four moves yield two rows with correct numbering") {
        val moves = List(
          MoveEntryDto("white", "e4"),
          MoveEntryDto("black", "e5"),
          MoveEntryDto("white", "Nf3"),
          MoveEntryDto("black", "Nc6")
        )
        val result = Logic.groupMovesByTwo(moves)
        assertTrue(
          result == List(
            (1, MoveEntryDto("white", "e4"), Some(MoveEntryDto("black", "e5"))),
            (
              2,
              MoveEntryDto("white", "Nf3"),
              Some(MoveEntryDto("black", "Nc6"))
            )
          )
        )
      },
      test("five moves: two full rows plus a dangling white") {
        val moves = List(
          MoveEntryDto("white", "e4"),
          MoveEntryDto("black", "e5"),
          MoveEntryDto("white", "Nf3"),
          MoveEntryDto("black", "Nc6"),
          MoveEntryDto("white", "Bb5")
        )
        val result = Logic.groupMovesByTwo(moves)
        assertTrue(
          result.size == 3,
          result(2) == (3, MoveEntryDto("white", "Bb5"), None)
        )
      }
    ),
    suite("promotionChoices")(
      test("offers Q/R/B/N mapped to piece-type names") {
        assertTrue(
          Logic.promotionChoices.map(_._1) == List("Q", "R", "B", "N"),
          Logic.promotionChoices.map(_._2) ==
            List("queen", "rook", "bishop", "knight")
        )
      }
    ),
    suite("humanizeDrawReason")(
      test("known reasons render as friendly phrases") {
        assertTrue(
          Logic.humanizeDrawReason("fiftyMoveRule") == "50-move rule",
          Logic.humanizeDrawReason(
            "threefoldRepetition"
          ) == "threefold repetition",
          Logic.humanizeDrawReason(
            "fivefoldRepetition"
          ) == "fivefold repetition",
          Logic.humanizeDrawReason("stalemate") == "stalemate",
          Logic.humanizeDrawReason(
            "insufficientMaterial"
          ) == "insufficient material"
        )
      },
      test("unknown reasons fall through unchanged") {
        assertTrue(Logic.humanizeDrawReason("agreement") == "agreement")
      }
    ),
    suite("decideInitialTheme")(
      test("stored 'dark' opts in to dark mode") {
        assertTrue(
          Logic.decideInitialTheme(Some("dark"), prefersDark = false) ==
            Logic.Theme.Dark
        )
      },
      test("stored 'light' resolves to light") {
        assertTrue(
          Logic.decideInitialTheme(Some("light"), prefersDark = true) ==
            Logic.Theme.Light
        )
      },
      test("no stored value defaults to light (OS preference ignored)") {
        // Light is the default for first-time visitors regardless of OS
        // setting — the prefersDark argument is retained but no longer
        // affects the outcome.
        assertTrue(
          Logic.decideInitialTheme(None, prefersDark = true) ==
            Logic.Theme.Light,
          Logic.decideInitialTheme(None, prefersDark = false) ==
            Logic.Theme.Light
        )
      },
      test("garbage stored value defaults to light") {
        // Defensive: tampered localStorage falls through to the default.
        assertTrue(
          Logic.decideInitialTheme(Some("not-a-theme"), prefersDark = true) ==
            Logic.Theme.Light,
          Logic.decideInitialTheme(Some("not-a-theme"), prefersDark = false) ==
            Logic.Theme.Light
        )
      }
    ),
    suite("capturedFromSquares")(
      test("starting position has no captures") {
        val (white, black) = Logic.capturedFromSquares(startingSquares)
        assertTrue(white.isEmpty, black.isEmpty)
      },
      test("removing a black pawn shows up in blackLost") {
        val squares = startingSquares.map { sq =>
          if sq.pos == "e7" then sq.copy(piece = None, pieceColor = None)
          else sq
        }
        val (white, black) = Logic.capturedFromSquares(squares)
        assertTrue(white.isEmpty, black == List("pawn"))
      },
      test("multiple captures sort by descending value") {
        // Remove black queen, a black rook, and two black pawns.
        val removed = Set("d8", "a8", "a7", "b7")
        val squares = startingSquares.map { sq =>
          if removed.contains(sq.pos) then
            sq.copy(piece = None, pieceColor = None)
          else sq
        }
        val (_, black) = Logic.capturedFromSquares(squares)
        assertTrue(black == List("queen", "rook", "pawn", "pawn"))
      },
      test(
        "under-promotion to a second queen still treats the lost pawn as captured"
      ) {
        // Replace the white e2 pawn with a queen, simulating a promoted-pawn
        // position. The promoted white pawn shows as a captured "pawn" since
        // it's missing from the starting count.
        val squares = startingSquares.map { sq =>
          if sq.pos == "e2" then
            sq.copy(piece = Some("queen"), pieceColor = Some("white"))
          else sq
        }
        val (white, _) = Logic.capturedFromSquares(squares)
        assertTrue(white == List("pawn"))
      },
      test("squares without piece info are ignored") {
        // A square missing pieceColor or piece is treated as empty —
        // exercises the collect-filter branch in capturedFromSquares.
        val squares = startingSquares :+
          SquareDto("z9", "light", Some("rook"), None) :+
          SquareDto("z8", "light", None, Some("white"))
        val (white, black) = Logic.capturedFromSquares(squares)
        assertTrue(white.isEmpty, black.isEmpty)
      }
    ),
    suite("spectate filters")(
      test("matchesFilter: All matches all; each chip matches its own token") {
        assertTrue(
          Logic.matchesFilter("pvp", Logic.SpectateFilter.All),
          Logic.matchesFilter("tournament", Logic.SpectateFilter.All),
          Logic.matchesFilter("pvp", Logic.SpectateFilter.Pvp),
          !Logic.matchesFilter("pvp", Logic.SpectateFilter.Pvbot),
          Logic.matchesFilter("pvbot", Logic.SpectateFilter.Pvbot),
          Logic.matchesFilter("lichess", Logic.SpectateFilter.Lichess),
          Logic.matchesFilter("tournament", Logic.SpectateFilter.Tournament)
        )
      },
      test("filterGames keeps only matching rows, order preserved") {
        val games = List(og("a", "pvp"), og("b", "tournament"), og("c", "pvp"))
        assertTrue(
          Logic
            .filterGames(games, Logic.SpectateFilter.Pvp)
            .map(_.id) == List("a", "c"),
          Logic.filterGames(games, Logic.SpectateFilter.All).size == 3
        )
      },
      test("chips carry live counts; empty type chips disabled, All enabled") {
        val games = List(og("a", "pvp"), og("b", "pvp"), og("c", "lichess"))
        val byFilter =
          Logic.spectateFilterChips(games).map { case (f, l, e) =>
            (f, (l, e))
          }.toMap
        assertTrue(
          Logic.spectateFilterChips(games).head._1 == Logic.SpectateFilter.All,
          byFilter(Logic.SpectateFilter.All) == ("All (3)", true),
          byFilter(Logic.SpectateFilter.Pvp) == ("PvP (2)", true),
          byFilter(Logic.SpectateFilter.Lichess) == ("Bot v Lichess (1)", true),
          byFilter(Logic.SpectateFilter.Pvbot) == ("PvBot (0)", false),
          byFilter(Logic.SpectateFilter.Tournament) == ("Tournament (0)", false)
        )
      },
      test("All chip stays enabled even with no games") {
        assertTrue(
          Logic
            .spectateFilterChips(Nil)
            .find(_._1 == Logic.SpectateFilter.All)
            .exists(_._3)
        )
      },
      test("gameBadge: full → Full/full, otherwise Live/live") {
        assertTrue(
          Logic.gameBadge(og("a", "pvp", spectateable = false)) ==
            ("Full", "full"),
          Logic.gameBadge(og("b", "pvp", spectateable = true)) ==
            ("Live", "live")
        )
      }
    ),
    suite("refresh")(
      test("refreshIntervals starts Off (None) — nothing polls by default") {
        assertTrue(
          Logic.refreshIntervals.head == (None, "Off"),
          Logic.refreshIntervals.map(_._2) == List("Off", "5s", "10s", "30s", "1m")
        )
      }
    ),
    suite("replayMoveState")(
      test("active = move that produced the shown frame; later moves muted") {
        assertTrue(
          // Showing the position after 2 half-moves (activePly = 2):
          Logic.replayMoveState(0, 2) == (false, false), // played, not active
          Logic.replayMoveState(1, 2) == (true, false),  // the active move
          Logic.replayMoveState(2, 2) == (false, true),  // not yet played → muted
          Logic.replayMoveState(3, 2) == (false, true),
          // Initial position (activePly 0): nothing active, all future.
          Logic.replayMoveState(0, 0) == (false, true),
          // Final frame (activePly N): the last move is active, nothing future.
          Logic.replayMoveState(4, 5) == (true, false)
        )
      }
    ),
    suite("movedSquares")(
      test("flags exactly the from/to of a quiet move") {
        // e2 pawn → e4: e2 emptied, e4 gained the pawn; d2 unchanged.
        val before = stateWith(
          SquareDto("e2", "light", Some("pawn"), Some("white")),
          SquareDto("e4", "dark", None, None),
          SquareDto("d2", "dark", Some("pawn"), Some("white"))
        )
        val after = stateWith(
          SquareDto("e2", "light", None, None),
          SquareDto("e4", "dark", Some("pawn"), Some("white")),
          SquareDto("d2", "dark", Some("pawn"), Some("white"))
        )
        assertTrue(Logic.movedSquares(before, after) == Set("e2", "e4"))
      },
      test("a capture flags both squares (occupant identity changed on the to)") {
        // black rook on d5 captured by the white pawn from c4.
        val before = stateWith(
          SquareDto("c4", "light", Some("pawn"), Some("white")),
          SquareDto("d5", "dark", Some("rook"), Some("black"))
        )
        val after = stateWith(
          SquareDto("c4", "light", None, None),
          SquareDto("d5", "dark", Some("pawn"), Some("white"))
        )
        assertTrue(Logic.movedSquares(before, after) == Set("c4", "d5"))
      },
      test("identical boards flag nothing") {
        val s = stateWith(SquareDto("e4", "light", Some("rook"), Some("white")))
        assertTrue(Logic.movedSquares(s, s).isEmpty)
      }
    ),
    suite("tournaments")(
      test("tournamentBadge maps statuses; canEnter only while created") {
        assertTrue(
          Logic.tournamentBadge("created") == ("Open", "waiting"),
          Logic.tournamentBadge("started") == ("Live", "live"),
          Logic.tournamentBadge("finished") == ("Done", "done"),
          Logic.tournamentBadge("weird") == ("weird", ""),
          Logic.canEnterTournament("created"),
          !Logic.canEnterTournament("started")
        )
      },
      test("orderTournaments flattens created → started → finished") {
        val list = Logic.TournamentList(
          created = List(tr("c1", "created")),
          started = List(tr("s1", "started"), tr("s2", "started")),
          finished = List(tr("f1", "finished"))
        )
        assertTrue(
          Logic.orderTournaments(list).map(_.id) == List("c1", "s1", "s2", "f1")
        )
      },
      test("TournamentList decodes the NowChess envelope, ignoring extras") {
        val json =
          """{"created":[{"id":"t1","fullName":"Tournament 0.1","nbPlayers":4,"status":"created","round":0,"clock":{"limit":300,"increment":2},"rated":true}],"started":[],"finished":[]}"""
        val decoded = json.fromJson[Logic.TournamentList]
        assertTrue(
          decoded.map(_.created.map(_.id)) == Right(List("t1")),
          decoded.map(_.created.head.fullName) == Right("Tournament 0.1"),
          decoded.map(_.created.head.nbPlayers) == Right(4)
        )
      }
    ),
    suite("analysis helpers")(
      test("evalText: signed pawns, mate-in-N marker") {
        assertTrue(
          Logic.evalText(150) == "+1.5",
          Logic.evalText(-200) == "-2.0",
          Logic.evalText(0) == "+0.0",
          // Forced mate → #N (mate in N moves), not a vanished number.
          Logic.evalText(100000) == "#",    // mate on the board
          Logic.evalText(99998) == "#1",    // 2 plies out
          Logic.evalText(99996) == "#2",    // 4 plies out
          Logic.evalText(-100000) == "-#",
          Logic.evalText(-99996) == "-#2"
        )
      },
      test("evalBarWhitePct clamps to [0,100]") {
        assertTrue(
          Logic.evalBarWhitePct(63.0) == 63.0,
          Logic.evalBarWhitePct(140.0) == 100.0,
          Logic.evalBarWhitePct(-5.0) == 0.0
        )
      },
      test("glyphClass maps each NAG symbol") {
        assertTrue(
          Logic.glyphClass(Some("!!")) == "brilliant",
          Logic.glyphClass(Some("!")) == "good",
          Logic.glyphClass(Some("!?")) == "interesting",
          Logic.glyphClass(Some("?!")) == "inaccuracy",
          Logic.glyphClass(Some("?")) == "mistake",
          Logic.glyphClass(Some("??")) == "blunder",
          Logic.glyphClass(None) == ""
        )
      },
      test("analysisForMove / analysisAtPly index by ply") {
        val a = GameAnalysisDto(
          OpeningDto(Some("B20"), "Sicilian Defense", "Sicilian", 1),
          List(
            MoveAnalysisDto(0, "white", "e4", 20, 53, 0, 100, "Book", None, "e4", Nil),
            MoveAnalysisDto(1, "black", "c5", 10, 49, 5, 96, "Best", None, "c5", Nil)
          ),
          95.0,
          90.0
        )
        assertTrue(
          Logic.analysisForMove(Some(a), 1).map(_.san) == Some("c5"),
          Logic.analysisForMove(Some(a), 9) == None,
          Logic.analysisForMove(None, 0) == None,
          Logic.analysisAtPly(Some(a), 0) == None,         // initial position
          Logic.analysisAtPly(Some(a), 1).map(_.san) == Some("e4"),
          Logic.analysisAtPly(Some(a), 2).map(_.san) == Some("c5")
        )
      },
      test("accuracyText formats one decimal percent") {
        assertTrue(Logic.accuracyText(92.37) == "92.4%")
      },
      test("openingLabel: eco + name, or bare name") {
        assertTrue(
          Logic.openingLabel(OpeningDto(Some("B90"), "Sicilian: Najdorf", "Sicilian", 10)) ==
            "B90 · Sicilian: Najdorf",
          Logic.openingLabel(OpeningDto(None, "Other", "Other", 0)) == "Other"
        )
      },
      test("replay + analysis agree on the active ply (cross-system wiring)") {
        // Clicking the move at flat index i sets activePly = i+1 (Main.moveCell).
        // At that ply the replay scrubber marks move i active, and the analysis
        // panels must select the SAME move — identical 0-based ply indexing.
        val a = GameAnalysisDto(
          OpeningDto(None, "x", "x", 0),
          List(
            MoveAnalysisDto(0, "white", "e4", 0, 50, 0, 100, "Best", None, "e4", Nil),
            MoveAnalysisDto(1, "black", "c5", 0, 50, 0, 100, "Best", None, "c5", Nil)
          ),
          100.0,
          100.0
        )
        val i = 1
        val activePly = i + 1 // what moveCell(i).onClick sets
        assertTrue(
          Logic.replayMoveState(i, activePly)._1,                       // move i highlighted
          !Logic.replayMoveState(i, activePly)._2,                      // …and not "future"
          Logic.analysisAtPly(Some(a), activePly).map(_.ply) == Some(i) // analysis selects move i
        )
      }
    ),
    suite("GameTitle")(
      test("vsBot places the player on their chosen side, bot on the other") {
        assertTrue(
          Logic.GameTitle.vsBot("Alice", "white", "Expert") ==
            Logic.GameTitle("Alice", "Bot (Expert)"),
          Logic.GameTitle.vsBot("Alice", "black", "Expert") ==
            Logic.GameTitle("Bot (Expert)", "Alice")
        )
      },
      test("vsBot falls back to \"You\" for a blank nickname") {
        assertTrue(
          Logic.GameTitle.vsBot("   ", "white", "Medium") ==
            Logic.GameTitle("You", "Bot (Medium)")
        )
      },
      test("players keeps named sides and fills blanks with the colour word") {
        assertTrue(
          Logic.GameTitle.players("Alice", "Bob") ==
            Logic.GameTitle("Alice", "Bob"),
          Logic.GameTitle.players("", " ") ==
            Logic.GameTitle("White", "Black")
        )
      },
      test("local is the generic colour-word title") {
        assertTrue(Logic.GameTitle.local == Logic.GameTitle("White", "Black"))
      }
    )
  )

  private def og(
      id: String,
      gameType: String,
      spectateable: Boolean = true
  ): OngoingGame =
    OngoingGame(id, gameType, "White", "Black", "ongoing", 0, 0, spectateable, None)

  private def tr(id: String, status: String): Logic.TournamentRow =
    Logic.TournamentRow(id, s"T $id", 2, status, 0)

  // Build a starting-position square list using piece-type names —
  // matches the wire format emitted by WebBoardView.toDto.
  private val startingSquares: List[SquareDto] =
    val backRank = List(
      "rook",
      "knight",
      "bishop",
      "queen",
      "king",
      "bishop",
      "knight",
      "rook"
    )
    (for
      rank <- (8 to 1 by -1).toList
      file <- ('a' to 'h').toList
    yield
      val pos = s"$file$rank"
      val (piece, color) = rank match
        case 8 => (Some(backRank(file - 'a')), Some("black"))
        case 7 => (Some("pawn"), Some("black"))
        case 2 => (Some("pawn"), Some("white"))
        case 1 => (Some(backRank(file - 'a')), Some("white"))
        case _ => (None, None)
      val sqColor = if (file - 'a' + rank) % 2 == 0 then "dark" else "light"
      SquareDto(pos, sqColor, piece, color)
    )
