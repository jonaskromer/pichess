package chess.obs

import zio.*
import zio.profiling.sampling.SamplingProfiler

import java.nio.file.{Files, Path, Paths}
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}

/** Opt-in ZIO fiber-aware sampling profiler. When the runtime env
  * `PICHESS_PROFILE=sampling` is set, [[wrap]] runs the service's main
  * program under [[zio.profiling.sampling.SamplingProfiler]] and writes
  * a flame-graph-compatible stack-collapsed file to
  * `/var/log/pichess/profile-<service>-<UTC-ts>.folded` when the program
  * terminates (either naturally or via SIGTERM).
  *
  * The file can be rendered with the canonical Brendan Gregg toolchain:
  *
  *   flamegraph.pl profile-<service>-<ts>.folded > flame.svg
  *
  * For useful call-site granularity, the codebase should be compiled
  * with the zio-profiling tagging compiler plugin enabled — see
  * `commonSettings` in `build.sbt` for the `PICHESS_PROFILE_BUILD=true`
  * opt-in. Without the plugin the profile still works but call-site
  * info collapses to the ZIO evaluation loop, which is uninformative.
  */
object ProfilerLayer:

  enum Mode:
    case Off, Sampling

  val EnvProfile      = "PICHESS_PROFILE"

  /** Container-side output directory. Compose bind-mounts the host's
    * `perf-reports/profiles/` to this path so dumps survive container
    * teardown.
    */
  private val OutputDir = "/var/log/pichess"

  private val timestamper =
    DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

  def modeFromEnv: UIO[Mode] =
    zio.System.env(EnvProfile).orDie.map {
      case Some(v) if v.trim.equalsIgnoreCase("sampling") => Mode.Sampling
      case _                                              => Mode.Off
    }

  private def outputPath(serviceName: String): Path =
    val ts = timestamper.format(Instant.now())
    Paths.get(OutputDir, s"profile-$serviceName-$ts.folded")

  private def ensureDir(dir: Path): UIO[Unit] =
    ZIO.attemptBlocking(Files.createDirectories(dir)).orDie.unit

  /** Wrap a service's main program. Off-mode is a literal pass-through
    * so test environments (which never set `PICHESS_PROFILE`) and the
    * default dev runtime pay zero profiler cost.
    *
    * On `Sampling` mode the profile result is collected for the
    * program's lifetime and dumped on completion. Errors writing the
    * dump are logged rather than failing the service: a failed
    * profile must not crash the service it's measuring.
    */
  /** Sampling period for the profiler. zio-profiling's default is 10ms;
    * we stretch to 20ms because the perf runs under measurement also
    * include other workloads (Gatling generator, async-profiler attach)
    * and a quieter sampler reduces measurement-side-effect bias.
    */
  private val samplingPeriod: Duration = 20.millis

  def wrap[R, E >: Throwable](
      serviceName: String,
      program: ZIO[R, E, Unit],
  ): ZIO[R, E, Unit] =
    modeFromEnv.flatMap {
      case Mode.Off => program
      case Mode.Sampling =>
        val out = outputPath(serviceName)
        for
          _      <- ensureDir(out.getParent)
          _      <- Console
                      .printLine(s"[profiler] sampling enabled → $out")
                      .orDie
          result <- SamplingProfiler(samplingPeriod).profile(program)
          _      <- result
                      .stackCollapseToFile(out.toString)
                      .catchAllCause(c =>
                        ZIO.logErrorCause("profile dump failed", c)
                      )
        yield ()
    }
