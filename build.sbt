import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}

ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.2"

// Tapir 1.11.x pulls in zio-json 0.7.x; we run 0.9.0. The breaking changes
// between those versions don't affect the APIs we rely on (Derive*,
// @jsonExplicitNull), so whitelist only that exact conflict. Other
// cross-version evictions will still surface as errors.
ThisBuild / libraryDependencySchemes ++= Seq(
  "dev.zio" %% "zio-json"                    % VersionScheme.Always,
  "dev.zio" % "zio-json_sjs1_3"              % VersionScheme.Always,
)

val zioVersion      = "2.1.24"
val zioHttpVersion  = "3.10.1"
val zioJsonVersion  = "0.9.0"
val zioKafkaVersion = "2.10.0"
val laminarVersion  = "17.2.0"
val tapirVersion    = "1.11.36"

/** Group jars into separate Docker layers so a one-file source change only
  * invalidates the (small) project-jar layer, not the (large) 3rd-party-jar
  * layer. Reused by every service block.
  */
val pichessLayerGrouping: PartialFunction[(File, String), Int] = {
  case (_, path) =>
    val orgs = Set("com.", "org.", "io.", "dev.zio", "scala.")
    if (path.startsWith("/opt/docker/lib/") && orgs.exists(o => path.contains(s"/$o")))
      2 // layer 2: 3rd-party jars (rare changes)
    else if (path.startsWith("/opt/docker/lib/"))
      3 // layer 3: own project jars (every build)
    else
      4 // layer 4: bin/, conf/, etc.
}

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio"          % zioVersion,
    "dev.zio" %% "zio-test"     % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  // Coverage is opt-in via `sbt coverage test coverageReport`. Leaving it on
  // by default bakes scoverage's runtime agent into every compiled class,
  // which tries to write coverage data to the host path at startup and
  // breaks Docker containers with a FileNotFoundException.
  coverageMinimumStmtTotal := 100,
  coverageFailOnMinimum    := true,
)

// domain is shared with the Scala.js web-ui, so deps must resolve on both
// JVM and JS sides via %%%.
lazy val domain = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("domain"))
  .settings(
    name := "pichess-domain",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio"          % zioVersion,
      "dev.zio" %%% "zio-test"     % zioVersion % Test,
      "dev.zio" %%% "zio-test-sbt" % zioVersion % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  )
  .jvmSettings(
    coverageMinimumStmtTotal := 100,
    coverageFailOnMinimum    := true,
  )
  .jsSettings(
    // scoverage's runtime agent uses java.io.File / TrieMap and doesn't link
    // under Scala.js — leaving instrumentation on breaks `sbt coverage`.
    coverageEnabled := false,
  )

// Wire DTOs — single source of truth for the HTTP contract, shared by gateway
// (JVM encoder) and web-ui (JS decoder) via zio-json's cross-compiled codecs.
lazy val api = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("api"))
  .settings(
    name := "pichess-api",
    libraryDependencies ++= Seq(
      "dev.zio"                     %%% "zio-json"       % zioJsonVersion,
      "com.softwaremill.sttp.tapir" %%% "tapir-core"     % tapirVersion,
      "com.softwaremill.sttp.tapir" %%% "tapir-json-zio" % tapirVersion,
      "dev.zio"                     %%% "zio-test"       % zioVersion % Test,
      "dev.zio"                     %%% "zio-test-sbt"   % zioVersion % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  )
  .jvmSettings(
    coverageMinimumStmtTotal := 100,
    coverageFailOnMinimum    := true,
  )
  .jsSettings(
    coverageEnabled := false,
  )

lazy val rules = project
  .in(file("rules"))
  .dependsOn(domain.jvm)
  .settings(commonSettings)
  .settings(name := "pichess-rules")

lazy val codec = project
  .in(file("codec"))
  .dependsOn(domain.jvm, rules)
  .settings(commonSettings)
  .settings(
    name := "pichess-codec",
    libraryDependencies ++= Seq(
      "dev.zio"                %% "zio-json"                 % zioJsonVersion,
      "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
      "com.lihaoyi"            %% "fastparse"                % "3.1.1",
    ),
  )

// Contract for the repository microservice — endpoint descriptions shared by
// its server (in `repository`) and its caller (`HttpGameRepository`).
lazy val repositoryApi = project
  .in(file("repository-api"))
  .settings(commonSettings)
  .settings(
    name := "pichess-repository-api",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-core"     % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-zio" % tapirVersion,
      "dev.zio"                     %% "zio-json"       % zioJsonVersion,
    ),
  )

// Kafka event ADT shared by gameService (producer) and repository (consumer).
// Single source of truth for what flows on the `chess.game-events` topic.
lazy val events = project
  .in(file("events"))
  .dependsOn(domain.jvm, codec)
  .settings(commonSettings)
  .settings(
    name := "pichess-events",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-json" % zioJsonVersion,
    ),
  )

// Generated zio-grpc stubs from proto/src/main/protobuf. Imported by gateway
// (client) and gameService (server). FEN strings cross the wire — Board/State
// stay out of the proto schema, that's the codec module's job.
lazy val proto = project
  .in(file("proto"))
  .settings(commonSettings)
  .settings(
    name := "pichess-proto",
    Compile / PB.targets := Seq(
      scalapb.gen(grpc = true)             -> (Compile / sourceManaged).value / "scalapb",
      scalapb.zio_grpc.ZioCodeGenerator    -> (Compile / sourceManaged).value / "scalapb",
    ),
    libraryDependencies ++= Seq(
      "io.grpc"                       %  "grpc-netty"            % "1.68.1",
      "com.thesamet.scalapb"          %% "scalapb-runtime-grpc"  % scalapb.compiler.Version.scalapbVersion,
      "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-core"         % "0.6.3",
    ),
    // Generated code; coverage policy doesn't apply, and Scala 3 deprecation
    // warnings (private[this], `_` wildcards, `using` clauses) belong to
    // upstream scalapb's codegen — silenced here so they don't drown out
    // signal in our own modules.
    coverageEnabled := false,
    Compile / scalacOptions += "-Wconf:any:s",
  )

lazy val repository = project
  .in(file("repository"))
  .dependsOn(domain.jvm, repositoryApi, codec, events)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "pichess-repository",
    Compile / mainClass := Some("chess.repository.RepositoryMain"),
    libraryDependencies ++= Seq(
      "dev.zio"                       %% "zio-http"              % zioHttpVersion,
      "dev.zio"                       %% "zio-kafka"             % zioKafkaVersion,
      "dev.zio"                       %% "zio-kafka-testkit"     % zioKafkaVersion % Test,
      "com.softwaremill.sttp.tapir"   %% "tapir-zio-http-server" % tapirVersion,
      "com.softwaremill.sttp.tapir"   %% "tapir-sttp-client"     % tapirVersion,
      "com.softwaremill.sttp.client3" %% "zio"                   % "3.11.0",
    ),
    Docker / packageName := "pichess-repository",
    Docker / version     := "latest",
    dockerBaseImage      := "eclipse-temurin:23-jre",
    dockerExposedPorts   := Seq(8091),
    dockerUpdateLatest   := true,
    Docker / dockerGroupLayers := pichessLayerGrouping,
    // Kafka-backed implementations need a live broker to exercise; covered
    // separately by docker-compose smoke tests, not by unit coverage.
    coverageExcludedFiles := ".*Kafka.*",
  )

lazy val gameService = project
  .in(file("game-service"))
  .dependsOn(domain.jvm, rules, codec, events, proto)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "pichess-game-service",
    Compile / mainClass := Some("chess.gameservice.GameServiceMain"),
    libraryDependencies ++= Seq(
      "dev.zio"          %% "zio-kafka"         % zioKafkaVersion,
      "dev.zio"          %% "zio-kafka-testkit" % zioKafkaVersion % Test,
      "io.grpc"          %  "grpc-services"     % "1.68.1",
    ),
    Docker / packageName := "pichess-game-service",
    Docker / version     := "latest",
    dockerBaseImage      := "eclipse-temurin:23-jre",
    dockerExposedPorts   := Seq(9000),
    dockerUpdateLatest   := true,
    Docker / dockerGroupLayers := pichessLayerGrouping,
    // Kafka- and gRPC-Server-Main-backed code needs a live broker / port to
    // exercise; covered by docker-compose smoke tests, not unit coverage.
    coverageExcludedFiles :=
      ".*Kafka.*;.*GameServiceMain.*;.*GrpcServer.*",
  )

lazy val gateway = project
  .in(file("gateway"))
  .dependsOn(gameService, codec, api.jvm, proto)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "pichess-gateway",
    Compile / mainClass := Some("chess.gateway.GatewayMain"),
    libraryDependencies ++= Seq(
      "dev.zio"                     %% "zio-http"                 % zioHttpVersion,
      "dev.zio"                     %% "zio-json"                 % zioJsonVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server"    % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle"  % tapirVersion,
      "io.grpc"                     %  "grpc-inprocess"           % "1.68.1" % Test,
    ),
    Docker / packageName := "pichess-gateway",
    Docker / version     := "latest",
    dockerBaseImage      := "eclipse-temurin:23-jre",
    dockerExposedPorts   := Seq(8090),
    dockerUpdateLatest   := true,
    Docker / dockerGroupLayers := pichessLayerGrouping,
    coverageExcludedFiles := ".*GatewayMain.*",
    // Copy the Scala.js output of web-ui into gateway's managed resources at
    // web/main.js so WebController can serve it from the classpath.
    Compile / resourceGenerators += Def.task {
      val report = (webUi / Compile / fastLinkJS).value
      val linkerDir =
        (webUi / Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
      val targetDir = (Compile / resourceManaged).value / "web"
      IO.createDirectory(targetDir)
      report.data.publicModules.toSeq.flatMap { m =>
        val src = linkerDir / m.jsFileName
        val dst = targetDir / m.jsFileName
        IO.copyFile(src, dst)
        val maybeMap = linkerDir / (m.jsFileName + ".map")
        val mapOut =
          if (maybeMap.exists()) {
            val mapDst = targetDir / (m.jsFileName + ".map")
            IO.copyFile(maybeMap, mapDst)
            Some(mapDst)
          } else None
        Seq(dst) ++ mapOut
      }
    }.taskValue,
  )

// TUI is currently a parser-only library. Runtime (stdin loop + REST client
// to the gateway) is documented future work — see docs/roadmap.md "TUI to
// REST". Once implemented, depends on api.jvm for the Tapir contract.
lazy val tui = project
  .in(file("tui"))
  .dependsOn(domain.jvm, codec)
  .settings(commonSettings)
  .settings(name := "pichess-tui")

lazy val webUi = project
  .in(file("web-ui"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(domain.js, api.js)
  .settings(
    name                            := "pichess-web-ui",
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "com.raquo"                   %%% "laminar"           % laminarVersion,
      "com.softwaremill.sttp.tapir" %%% "tapir-sttp-client" % tapirVersion,
      "dev.zio"                     %%% "zio-test"          % zioVersion % Test,
      "dev.zio"                     %%% "zio-test-sbt"      % zioVersion % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Scoverage doesn't instrument Scala.js output, so coverage stays off here
    // even when someone runs `sbt coverage test coverageReport` globally.
    coverageEnabled := false,
  )

lazy val root = project
  .in(file("."))
  .aggregate(
    domain.jvm,
    domain.js,
    api.jvm,
    api.js,
    rules,
    codec,
    repositoryApi,
    events,
    proto,
    repository,
    gameService,
    gateway,
    tui,
    webUi,
  )
  .settings(
    name := "pichess",
    // `sbt run` at the root has no single Main since the app monolith was
    // split into separate gateway / gameService / repository services. Use
    // `sbt <svc>/run` for individual services or `docker compose up` for the
    // integrated stack.
  )
