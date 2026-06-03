package chess.persistence

import zio.*
import zio.test.*

object MutationSpec extends ZIOSpecDefault:

  // Tiny domain for the tests: a string state with a string event log.
  private type M = Mutation[String, String, String]

  private val base: M = Mutation.from("id-1", "pre", "after-1", "event-1")

  def spec = suite("Mutation")(
    suite("changed")(
      test("true when state != pre") {
        assertTrue(base.changed)
      },
      test("false when state == pre (no-op build via unchanged)") {
        val m: M = Mutation.unchanged("id-1", "pre")
        assertTrue(!m.changed && m.events.isEmpty)
      },
      test("false when amend brings state back to pre") {
        val m = base.amend(_ => Some(("pre", "rolled-back")))
        assertTrue(!m.changed, m.events == Chunk("event-1", "rolled-back"))
      },
    ),
    suite("amend")(
      test("Some replaces state and appends event") {
        val m = base.amend(s => Some((s + "-amended", "event-2")))
        assertTrue(
          m.state == "after-1-amended",
          m.events == Chunk("event-1", "event-2"),
        )
      },
      test("None is a no-op") {
        val m = base.amend(_ => None)
        assertTrue(m == base)
      },
    ),
    suite("amendState")(
      test("Some replaces state and does NOT add event") {
        val m = base.amendState(_ => Some("after-2"))
        assertTrue(m.state == "after-2", m.events == Chunk("event-1"))
      },
      test("None is a no-op") {
        val m = base.amendState(_ => None)
        assertTrue(m == base)
      },
    ),
    suite("commit")(
      test("changed Mutation saves once and publishes every event") {
        for
          saved <- Ref.make(List.empty[(String, String)])
          pubs  <- Ref.make(List.empty[String])
          m      = base.amend(_ => Some(("after-2", "event-2")))
          _     <- Mutation.commit[Any, Nothing, String, String, String](
                     m,
                     save    = (id, s) => saved.update((id, s) :: _),
                     publish = ev => pubs.update(ev :: _),
                   )
          ss    <- saved.get
          ps    <- pubs.get
        yield assertTrue(
          ss == List(("id-1", "after-2")),
          ps.reverse == List("event-1", "event-2"),
        )
      },
      test("unchanged Mutation skips save but still publishes events") {
        // Hand-craft: pre == state but events present (rare but possible
        // for operations that emit a domain event without persisting state).
        val m: M = Mutation.unchanged("id-1", "pre")
          .amend(_ => Some(("pre", "side-event"))) // event but no state change
        for
          saved <- Ref.make(List.empty[(String, String)])
          pubs  <- Ref.make(List.empty[String])
          _     <- Mutation.commit[Any, Nothing, String, String, String](
                     m,
                     save    = (id, s) => saved.update((id, s) :: _),
                     publish = ev => pubs.update(ev :: _),
                   )
          ss    <- saved.get
          ps    <- pubs.get
        yield assertTrue(ss.isEmpty, ps == List("side-event"))
      },
      test("save failure propagates") {
        val failed: IO[String, Unit] = ZIO.fail("save-boom")
        for exit <- Mutation.commit[Any, String, String, String, String](
                      base,
                      save    = (_, _) => failed,
                      publish = _ => ZIO.unit,
                    ).exit
        yield assertTrue(exit.causeOption.exists(_.failureOption.contains("save-boom")))
      },
      test("publish failure propagates") {
        for exit <- Mutation.commit[Any, String, String, String, String](
                      base,
                      save    = (_, _) => ZIO.unit,
                      publish = _ => ZIO.fail("publish-boom"),
                    ).exit
        yield assertTrue(exit.causeOption.exists(_.failureOption.contains("publish-boom")))
      },
    ),
  )
