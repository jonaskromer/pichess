package chess.view

import chess.model.board.{GameState, Position}
import chess.model.piece.{Color, Piece, PieceType}
import zio.test.*

object BoardViewSpec extends ZIOSpecDefault:

  // Strip ANSI escape codes so we can check plain text content
  private def stripAnsi(s: String): String =
    s.replaceAll("\u001b\\[[^m]*m", "")

  private val rendered = stripAnsi(BoardView.render(GameState.initial))

  private val flippedRendered = stripAnsi(
    BoardView.render(GameState.initial, flipped = true)
  )

  def spec = suite("BoardView.render")(
    test("include all White piece symbols") {
      assertTrue(
        rendered.contains("♔"),
        rendered.contains("♕"),
        rendered.contains("♖"),
        rendered.contains("♗"),
        rendered.contains("♘"),
        rendered.contains("♙")
      )
    },
    test("include all Black piece symbols") {
      assertTrue(
        rendered.contains("♚"),
        rendered.contains("♛"),
        rendered.contains("♜"),
        rendered.contains("♝"),
        rendered.contains("♞"),
        rendered.contains("♟")
      )
    },
    test("include all row labels") {
      assertTrue((1 to 8).forall(row => rendered.contains(row.toString)))
    },
    test("include all column labels") {
      assertTrue(('a' to 'h').forall(col => rendered.contains(col.toString)))
    },
    test("show 8 ranks in the output") {
      val rankLines = rendered.linesIterator
        .filter(l => l.trim.headOption.exists(_.isDigit))
        .toList
      assertTrue(rankLines.size == 8)
    },
    test("show rank 8 at the top when not flipped") {
      val rankLines = rendered.linesIterator
        .filter(l => l.trim.headOption.exists(_.isDigit))
        .toList
      assertTrue(rankLines.head.startsWith("8"))
    },
    test("show rank 1 at the bottom when not flipped") {
      val rankLines = rendered.linesIterator
        .filter(l => l.trim.headOption.exists(_.isDigit))
        .toList
      assertTrue(rankLines.last.startsWith("1"))
    },
    test("show column a first when not flipped") {
      assertTrue(rendered.linesIterator.next().trim.startsWith("a"))
    },
    suite("when flipped")(
      test("include all White piece symbols") {
        assertTrue(
          flippedRendered.contains("♔"),
          flippedRendered.contains("♕"),
          flippedRendered.contains("♖"),
          flippedRendered.contains("♗"),
          flippedRendered.contains("♘"),
          flippedRendered.contains("♙")
        )
      },
      test("include all Black piece symbols") {
        assertTrue(
          flippedRendered.contains("♚"),
          flippedRendered.contains("♛"),
          flippedRendered.contains("♜"),
          flippedRendered.contains("♝"),
          flippedRendered.contains("♞"),
          flippedRendered.contains("♟")
        )
      },
      test("show rank 1 at the top") {
        val rankLines = flippedRendered.linesIterator
          .filter(l => l.trim.headOption.exists(_.isDigit))
          .toList
        assertTrue(rankLines.head.startsWith("1"))
      },
      test("show rank 8 at the bottom") {
        val rankLines = flippedRendered.linesIterator
          .filter(l => l.trim.headOption.exists(_.isDigit))
          .toList
        assertTrue(rankLines.last.startsWith("8"))
      },
      test("show column h first") {
        assertTrue(flippedRendered.linesIterator.next().trim.startsWith("h"))
      }
    ),
    suite("check highlight")(
      test("use red foreground for checked king") {
        val state = GameState(
          Map(
            Position('e', 1) -> Piece(Color.White, PieceType.King),
            Position('e', 8) -> Piece(Color.Black, PieceType.Rook)
          ),
          Color.White,
          inCheck = true
        )
        val raw = BoardView.render(state)
        // Red bold ANSI: \u001b[1;91m
        assertTrue(raw.contains("\u001b[1;91m"))
      },
      test("not use red foreground when not in check") {
        val raw = BoardView.render(GameState.initial)
        assertTrue(!raw.contains("\u001b[1;91m"))
      }
    )
  )
