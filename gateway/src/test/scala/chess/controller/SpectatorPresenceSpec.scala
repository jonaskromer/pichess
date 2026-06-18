package chess.controller

import zio.*
import zio.test.*
import zio.test.Assertion.*

object SpectatorPresenceSpec extends ZIOSpecDefault:

  def spec = suite("SpectatorPresence")(
    test("admits + counts a spectator when no policy is set (unrestricted)") {
      for
        p      <- SpectatorPresence.make
        before <- p.changes("g").runHead
        // Inside the scope the slot is held; the count reads 1. Closing
        // the scope must release it.
        during <- ZIO.scoped(p.admit("g") *> p.changes("g").runHead)
        after  <- p.changes("g").runHead
      yield assertTrue(
        before.contains(0),
        during.contains(1),
        after.contains(0)
      )
    },
    test("refuses when spectating is disallowed") {
      for
        p    <- SpectatorPresence.make
        _    <- p.setPolicy("g", allowSpectate = false, limit = 0)
        exit <- ZIO.scoped(p.admit("g")).exit
      yield assert(exit)(fails(equalTo(SpectatorRejection.NotAllowed)))
    },
    test("caps concurrent spectators at the limit") {
      for
        p    <- SpectatorPresence.make
        _    <- p.setPolicy("g", allowSpectate = true, limit = 1)
        // First spectator takes the only slot; the second is refused
        // while the first is still seated.
        exit <- ZIO.scoped(p.admit("g") *> ZIO.scoped(p.admit("g")).exit)
      yield assert(exit)(fails(equalTo(SpectatorRejection.Full)))
    },
    test("frees the slot when a spectator's scope closes") {
      for
        p    <- SpectatorPresence.make
        _    <- p.setPolicy("g", allowSpectate = true, limit = 1)
        _    <- ZIO.scoped(p.admit("g")) // occupy, then release on close
        exit <- ZIO.scoped(p.admit("g")).exit
      yield assertTrue(exit.isSuccess)
    },
    test("a non-positive limit means no cap") {
      for
        p    <- SpectatorPresence.make
        _    <- p.setPolicy("g", allowSpectate = true, limit = 0)
        exit <- ZIO.scoped(
                  p.admit("g") *> p.admit("g") *> ZIO.scoped(p.admit("g")).exit
                )
      yield assertTrue(exit.isSuccess)
    }
  )
