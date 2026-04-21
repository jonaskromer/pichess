ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.2"

val zioVersion     = "2.1.24"
val zioHttpVersion = "3.10.1"
val zioJsonVersion = "0.9.0"

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio"          % zioVersion,
    "dev.zio" %% "zio-test"     % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
  ),
  testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  coverageEnabled          := true,
  coverageMinimumStmtTotal := 100,
  coverageFailOnMinimum    := true,
)

lazy val domain = project
  .in(file("domain"))
  .settings(commonSettings)
  .settings(name := "pichess-domain")

lazy val rules = project
  .in(file("rules"))
  .dependsOn(domain)
  .settings(commonSettings)
  .settings(name := "pichess-rules")

lazy val codec = project
  .in(file("codec"))
  .dependsOn(domain, rules)
  .settings(commonSettings)
  .settings(
    name := "pichess-codec",
    libraryDependencies ++= Seq(
      "dev.zio"                %% "zio-json"                 % zioJsonVersion,
      "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
      "com.lihaoyi"            %% "fastparse"                % "3.1.1",
    ),
  )

lazy val repository = project
  .in(file("repository"))
  .dependsOn(domain)
  .settings(commonSettings)
  .settings(name := "pichess-repository")

lazy val gameService = project
  .in(file("game-service"))
  .dependsOn(domain, rules, codec, repository)
  // codec already depends on rules, but listing rules here keeps it explicit.
  .settings(commonSettings)
  .settings(name := "pichess-game-service")

lazy val gateway = project
  .in(file("gateway"))
  .dependsOn(gameService, codec)
  .settings(commonSettings)
  .settings(
    name := "pichess-gateway",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-http" % zioHttpVersion,
      "dev.zio" %% "zio-json" % zioJsonVersion,
    ),
  )

lazy val tui = project
  .in(file("tui"))
  .dependsOn(gameService, codec)
  .settings(commonSettings)
  .settings(name := "pichess-tui")

lazy val app = project
  .in(file("app"))
  .dependsOn(gameService, repository, codec, tui, gateway)
  .settings(commonSettings)
  .settings(
    name := "pichess-app",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-http"    % zioHttpVersion,
      "dev.zio" %% "zio-process" % "0.7.2",
    ),
  )

lazy val root = project
  .in(file("."))
  .aggregate(domain, rules, codec, repository, gameService, gateway, tui, app)
  .settings(
    name := "pichess",
    run := (app / Compile / run).evaluated,
  )
