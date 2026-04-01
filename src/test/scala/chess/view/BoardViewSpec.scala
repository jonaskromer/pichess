package chess.view

import chess.model.board.GameState
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BoardViewSpec extends AnyFlatSpec with Matchers:

  // Strip ANSI escape codes so we can check plain text content
  private def stripAnsi(s: String): String =
    s.replaceAll("\u001b\\[[^m]*m", "")

  private val rendered = stripAnsi(BoardView.render(GameState.initial))

  "BoardView.render" should "include all White piece symbols" in:
    rendered should include("♔")
    rendered should include("♕")
    rendered should include("♖")
    rendered should include("♗")
    rendered should include("♘")
    rendered should include("♙")

  it should "include all Black piece symbols" in:
    rendered should include("♚")
    rendered should include("♛")
    rendered should include("♜")
    rendered should include("♝")
    rendered should include("♞")
    rendered should include("♟")

  it should "include all row labels" in:
    (1 to 8).foreach: row =>
      rendered should include(row.toString)

  it should "include all column labels" in:
    ('a' to 'h').foreach: col =>
      rendered should include(col.toString)

  it should "show 8 ranks in the output" in:
    // Each rank is rendered as a line starting with the rank number
    val rankLines = rendered.linesIterator
      .filter(l => l.trim.headOption.exists(_.isDigit))
      .toList
    rankLines.size shouldBe 8

  it should "show rank 8 at the top when not flipped" in:
    val rankLines = rendered.linesIterator
      .filter(l => l.trim.headOption.exists(_.isDigit))
      .toList
    rankLines.head should startWith("8")

  it should "show rank 1 at the bottom when not flipped" in:
    val rankLines = rendered.linesIterator
      .filter(l => l.trim.headOption.exists(_.isDigit))
      .toList
    rankLines.last should startWith("1")

  it should "show column a first when not flipped" in:
    rendered.linesIterator.next().trim should startWith("a")

  private val flippedRendered = stripAnsi(
    BoardView.render(GameState.initial, flipped = true)
  )

  "BoardView.render when flipped" should "include all White piece symbols" in:
    flippedRendered should include("♔")
    flippedRendered should include("♕")
    flippedRendered should include("♖")
    flippedRendered should include("♗")
    flippedRendered should include("♘")
    flippedRendered should include("♙")

  it should "include all Black piece symbols" in:
    flippedRendered should include("♚")
    flippedRendered should include("♛")
    flippedRendered should include("♜")
    flippedRendered should include("♝")
    flippedRendered should include("♞")
    flippedRendered should include("♟")

  it should "show rank 1 at the top" in:
    val rankLines = flippedRendered.linesIterator
      .filter(l => l.trim.headOption.exists(_.isDigit))
      .toList
    rankLines.head should startWith("1")

  it should "show rank 8 at the bottom" in:
    val rankLines = flippedRendered.linesIterator
      .filter(l => l.trim.headOption.exists(_.isDigit))
      .toList
    rankLines.last should startWith("8")

  it should "show column h first" in:
    flippedRendered.linesIterator.next().trim should startWith("h")
