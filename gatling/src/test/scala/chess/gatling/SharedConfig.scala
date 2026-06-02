package chess.gatling

/** Pulls baseURL + load-shape parameters from system properties so the same
  * simulation binary can drive different backends (PICHESS_BACKEND swap)
  * and different intensities without recompiling.
  *
  * Override on the command line:
  *   sbt -DpichessGatewayUrl=http://localhost:8090 \
  *       -DpichessLobbyUrl=http://localhost:8092 \
  *       -DpichessUsers=20 -DpichessRampSeconds=10 \
  *       -DpichessPeakUsers=200 -DpichessHoldSeconds=120 \
  *       -DpichessRatePerSec=5 \
  *       'gatling/Gatling/test'
  */
object SharedConfig:

  val gatewayUrl: String =
    sys.props.getOrElse("pichessGatewayUrl", "http://localhost:8090")

  val lobbyUrl: String =
    sys.props.getOrElse("pichessLobbyUrl", "http://localhost:8092")

  /** Closed-loop user count for simple ramp simulations (e.g. the smoke
    * `GameSimulation` / `LobbySimulation`). Each user runs the scenario
    * once and exits.
    */
  val users: Int =
    sys.props.get("pichessUsers").flatMap(_.toIntOption).getOrElse(10)

  /** Duration (seconds) over which the closed-loop users are introduced. */
  val rampSeconds: Int =
    sys.props.get("pichessRampSeconds").flatMap(_.toIntOption).getOrElse(5)

  /** Peak concurrent / burst user count for stress, spike and volume
    * simulations.
    */
  val peakUsers: Int =
    sys.props.get("pichessPeakUsers").flatMap(_.toIntOption).getOrElse(50)

  /** Hold duration (seconds) for the plateau after the ramp in stress
    * mode, or the constant-rate window in endurance mode.
    */
  val holdSeconds: Int =
    sys.props.get("pichessHoldSeconds").flatMap(_.toIntOption).getOrElse(60)

  /** Open-loop arrival rate (users per second) for endurance / steady
    * background traffic between spikes.
    */
  val ratePerSec: Int =
    sys.props.get("pichessRatePerSec").flatMap(_.toIntOption).getOrElse(5)

  /** A short legal opening sequence in coordinate notation that every
    * scenario can replay regardless of backend or starting state.
    */
  val openingMoves: List[String] =
    List(
      "e2 e4",
      "e7 e5",
      "g1 f3",
      "b8 c6",
      "f1 b5",
      "a7 a6",
      "b5 a4",
      "g8 f6"
    )
