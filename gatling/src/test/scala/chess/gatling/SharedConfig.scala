package chess.gatling

/** Pulls baseURL + load-shape parameters from system properties so the same
  * simulation binary can drive different backends (PICHESS_BACKEND swap)
  * and different intensities without recompiling.
  *
  * Override on the command line:
  *   sbt -DpichessGatewayUrl=http://localhost:8090 \
  *       -DpichessLobbyUrl=http://localhost:8092 \
  *       -DpichessUsers=20 -DpichessRampSeconds=10 \
  *       'gatling/Gatling/test'
  */
object SharedConfig:

  val gatewayUrl: String =
    sys.props.getOrElse("pichessGatewayUrl", "http://localhost:8090")

  val lobbyUrl: String =
    sys.props.getOrElse("pichessLobbyUrl", "http://localhost:8092")

  val users: Int =
    sys.props.get("pichessUsers").flatMap(_.toIntOption).getOrElse(10)

  val rampSeconds: Int =
    sys.props.get("pichessRampSeconds").flatMap(_.toIntOption).getOrElse(5)

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
