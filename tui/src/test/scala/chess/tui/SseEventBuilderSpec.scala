package chess.tui

import chess.api.{BoardStateDto, GameStatusDto}
import chess.tui.SseEventBuilder.{Builder, Event}
import zio.json.*
import zio.test.*

object SseEventBuilderSpec extends ZIOSpecDefault:

  private val sampleDto = BoardStateDto(
    squares = Nil,
    activeColor = "white",
    moveLog = Nil,
    error = None,
    inCheck = false,
    checkedKingPos = None,
    status = GameStatusDto.Playing
  )

  def spec = suite("SseEventBuilder.Builder")(
    test("starts empty") {
      val b = Builder.empty
      assertTrue(
        b.eventType.isEmpty,
        b.data.isEmpty,
        b.dispatched.isEmpty
      )
    },
    test("append('event: state') records the event type") {
      val b = Builder.empty.append("event: state")
      assertTrue(b.eventType.contains("state"), b.data.isEmpty)
    },
    test("append('data: x') records a data line") {
      val b = Builder.empty.append("data: x")
      assertTrue(b.data == Vector("x"))
    },
    test("append accepts data without space after colon") {
      val b = Builder.empty.append("data:x")
      assertTrue(b.data == Vector("x"))
    },
    test("append ignores lines whose key isn't event/data") {
      val b = Builder.empty.append("id: 42").append("retry: 1000")
      assertTrue(b.eventType.isEmpty, b.data.isEmpty)
    },
    test("append ignores lines without a colon (no key match)") {
      val b = Builder.empty.append("comment-no-colon")
      assertTrue(b.eventType.isEmpty, b.data.isEmpty)
    },
    test("append clears a previously-dispatched event") {
      val dispatched =
        Builder.empty.append("event: state").append("data: {}").dispatch
      val next = dispatched.append("event: state")
      assertTrue(dispatched.dispatched.isDefined, next.dispatched.isEmpty)
    },
    test("dispatch on a valid state event returns Right(State)") {
      val json = sampleDto.toJson
      val b = Builder.empty
        .append("event: state")
        .append(s"data: $json")
        .dispatch
      assertTrue(b.dispatched == Some(Right(Event.State(sampleDto))))
    },
    test("dispatch concatenates multiple data lines with newlines") {
      // Hand-build a JSON payload whose halves each contain newline-safe
      // characters so the `data.mkString("\n")` step has to actually
      // glue them back. We use an array literal payload because zio-json
      // tolerates a `\n` between tokens but rejects one inside a string.
      val firstHalf =
        """{"squares":[],"activeColor":"white","moveLog":[],"error":null,"""
      val secondHalf =
        """"inCheck":false,"checkedKingPos":null,"status":{"kind":"playing","winner":null,"reason":null}}"""
      val b = Builder.empty
        .append("event: state")
        .append(s"data: $firstHalf")
        .append(s"data: $secondHalf")
        .dispatch
      assertTrue(
        b.dispatched == Some(Right(Event.State(sampleDto))),
        b.data.isEmpty // builder cleared after dispatch
      )
    },
    test("dispatch on an unknown event type returns Left") {
      val b =
        Builder.empty.append("event: ping").append("data: hi").dispatch
      assertTrue(b.dispatched == Some(Left("unknown event type: ping")))
    },
    test("dispatch with data but no event type returns 'event without type'") {
      val b = Builder.empty.append("data: x").dispatch
      assertTrue(b.dispatched == Some(Left("event without type")))
    },
    test("dispatch with nothing buffered returns 'empty event'") {
      val b = Builder.empty.dispatch
      assertTrue(b.dispatched == Some(Left("empty event")))
    },
    test("dispatch on a state event with malformed JSON returns decode error") {
      val b = Builder.empty
        .append("event: state")
        .append("data: not-json")
        .dispatch
      val msg = b.dispatched match
        case Some(Left(s)) => s
        case _             => ""
      assertTrue(msg.startsWith("state decode failed:"))
    }
  )
