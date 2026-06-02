package chess.opt

import zio.*

/** Marks a pair of implementations of `T` that the performance
  * experiment A/B's against each other.
  *
  * `default` is what the service Main picks when no override is set —
  * the implementation we believe (or hope) will be the better one for
  * the shipping default workload. `baseline` is what existed in the
  * codebase before the alternative was written, kept available so the
  * experiment can measure the delta.
  *
  * The names are deliberately NOT "optimised" / "naive": the perf
  * experiment is what proves which one wins on a given workload, not
  * our priors. `default` just means "the one that runs when you
  * `make build` without flipping any selector".
  *
  * Defining an `Optimisation[T]` instance is a one-line opt-in:
  * colocated with the implementation module that defines the
  * alternative. Service Mains call [[Optimisation.select]] and the
  * right layer is picked at startup based on the `PICHESS_OPT_<name>`
  * env var (or the global `PICHESS_OPT_ALL` knob).
  */
trait Optimisation[T]:

  /** Short, all-caps tag used to build the env var name:
    * `PICHESS_OPT_<name>` accepts the string values `default` or
    * `baseline` (case-insensitive). Anything else falls back to
    * `default`.
    */
  def name: String

  /** Layer constructed when no override applies. */
  def default: ZLayer[Any, Throwable, T]

  /** Layer constructed when the env says `baseline` or when the global
    * `PICHESS_OPT_ALL=baseline` knob is set.
    */
  def baseline: ZLayer[Any, Throwable, T]

object Optimisation:

  /** Pick the default or baseline layer for `T` based on the JVM's
    * env. Reads `sys.env`; for unit testing without the JVM env, use
    * [[selectWith]] with a custom env function.
    */
  def select[T](using o: Optimisation[T]): ZLayer[Any, Throwable, T] =
    selectWith(sys.env.get)

  /** Same as [[select]] but with an explicit env-lookup function. */
  def selectWith[T](
      env: String => Option[String]
  )(using o: Optimisation[T]): ZLayer[Any, Throwable, T] =
    if useBaseline(o.name, env) then o.baseline else o.default

  /** Resolve whether to pick the baseline side of a named optimisation
    * given an env lookup. Exposed (rather than private) so the perf
    * report can render the active selector state alongside the run
    * metadata, using the same resolution rules as the runtime.
    *
    * Resolution order:
    *   1. `PICHESS_OPT_<name>=baseline` → baseline
    *   2. `PICHESS_OPT_<name>=default` → default (explicit)
    *   3. `PICHESS_OPT_<name>=<anything else>` → default (defensive)
    *   4. unset, but `PICHESS_OPT_ALL=baseline` → baseline (the
    *      "regress everything for the headline A/B" knob)
    *   5. unset, no global → default
    */
  def useBaseline(name: String, env: String => Option[String]): Boolean =
    val perComponent = env(s"PICHESS_OPT_$name").map(_.toLowerCase)
    perComponent match
      case Some("baseline") => true
      case Some("default")  => false
      case _ =>
        env("PICHESS_OPT_ALL").map(_.toLowerCase).contains("baseline")
