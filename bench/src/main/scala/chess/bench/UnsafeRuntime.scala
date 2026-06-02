package chess.bench

import zio.{IO, Runtime, Unsafe}

/** Bridge from JMH's synchronous benchmark methods to the codec/rules APIs
  * that return `IO[GameError, A]`. Created once per JVM via JMH's `@State`
  * machinery so the per-invocation cost is a single `unsafe.run(...)`.
  *
  * Profiling overhead matters for these microbenchmarks — using the shared
  * `Runtime.default` avoids the per-call fiber-runtime allocation a fresh
  * runtime would incur.
  */
object UnsafeRuntime:

  private val runtime = Runtime.default

  inline def run[E, A](io: IO[E, A]): A =
    Unsafe.unsafe { implicit u =>
      runtime.unsafe.run(io).getOrThrowFiberFailure()
    }
