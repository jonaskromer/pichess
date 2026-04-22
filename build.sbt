import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}

ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.2"

// Tapir 1.11.x pulls in zio-json 0.7.x; we run 0.9.0. The breaking changes
// between those versions don't affect the APIs we rely on (Derive*,
// @jsonExplicitNull), so downgrade eviction errors to warnings.
ThisBuild / evictionErrorLevel := Level.Info

val zioVersion     = "2.1.24"
val zioHttpVersion = "3.10.1"
val zioJsonVersion = "0.9.0"
val laminarVersion = "17.2.0"
val tapirVersion   = "1.11.36"

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

lazy val repository = project
  .in(file("repository"))
  .dependsOn(domain.jvm, repositoryApi, codec)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "pichess-repository",
    Compile / mainClass := Some("chess.repository.RepositoryMain"),
    libraryDependencies ++= Seq(
      "dev.zio"                       %% "zio-http"              % zioHttpVersion,
      "com.softwaremill.sttp.tapir"   %% "tapir-zio-http-server" % tapirVersion,
      "com.softwaremill.sttp.tapir"   %% "tapir-sttp-client"     % tapirVersion,
      "com.softwaremill.sttp.client3" %% "zio"                   % "3.11.0",
    ),
    Docker / packageName := "pichess-repository",
    Docker / version     := "latest",
    dockerBaseImage      := "eclipse-temurin:23-jre",
    dockerExposedPorts   := Seq(8091),
    dockerUpdateLatest   := true,
  )

lazy val gameService = project
  .in(file("game-service"))
  .dependsOn(domain.jvm, rules, codec, repository)
  .settings(commonSettings)
  .settings(name := "pichess-game-service")

lazy val gateway = project
  .in(file("gateway"))
  .dependsOn(gameService, codec, api.jvm)
  .settings(commonSettings)
  .settings(
    name := "pichess-gateway",
    libraryDependencies ++= Seq(
      "dev.zio"                     %% "zio-http"                 % zioHttpVersion,
      "dev.zio"                     %% "zio-json"                 % zioJsonVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server"    % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle"  % tapirVersion,
    ),
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

lazy val tui = project
  .in(file("tui"))
  .dependsOn(gameService, codec)
  .settings(commonSettings)
  .settings(name := "pichess-tui")

lazy val app = project
  .in(file("app"))
  .dependsOn(gameService, repository, codec, tui, gateway)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name                := "pichess-app",
    Compile / mainClass := Some("chess.Main"),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-http"    % zioHttpVersion,
      "dev.zio" %% "zio-process" % "0.7.2",
    ),
    Docker / packageName := "pichess-app",
    Docker / version     := "latest",
    dockerBaseImage      := "eclipse-temurin:23-jre",
    dockerExposedPorts   := Seq(8090),
    dockerUpdateLatest   := true,
  )

lazy val webUi = project
  .in(file("web-ui"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(domain.js, api.js)
  .settings(
    name                            := "pichess-web-ui",
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "com.raquo"                   %%% "laminar"          % laminarVersion,
      "com.softwaremill.sttp.tapir" %%% "tapir-sttp-client" % tapirVersion,
    ),
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
    repository,
    gameService,
    gateway,
    tui,
    app,
    webUi,
  )
  .settings(
    name := "pichess",
    run  := (app / Compile / run).evaluated,
  )
