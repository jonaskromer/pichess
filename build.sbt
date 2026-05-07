import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}

ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.2"

// Tapir 1.11.x pulls in zio-json 0.7.x; we run 0.9.0. The breaking changes
// between those versions don't affect the APIs we rely on (Derive*,
// @jsonExplicitNull), so whitelist only that exact conflict. Other
// cross-version evictions will still surface as errors.
ThisBuild / libraryDependencySchemes ++= Seq(
  "dev.zio" %% "zio-json"        % VersionScheme.Always,
  "dev.zio" % "zio-json_sjs1_3"  % VersionScheme.Always,
  // zio-jdbc 0.1.2 still pins zio-schema 0.4.x; the transitive 1.x bumps
  // pulled in by zio-http and zio-redis ARE binary-compatible at the
  // surface zio-jdbc uses (Schema, BinaryCodec). Whitelist the eviction
  // rather than pin zio-jdbc to an older line.
  "dev.zio" %% "zio-schema"             % VersionScheme.Always,
  "dev.zio" %% "zio-schema-derivation"  % VersionScheme.Always,
  "dev.zio" %% "zio-schema-json"        % VersionScheme.Always,
  "dev.zio" %% "zio-schema-protobuf"    % VersionScheme.Always,
)

val zioVersion       = "2.1.24"
val zioHttpVersion   = "3.10.1"
val zioJsonVersion   = "0.9.0"
val zioKafkaVersion  = "2.10.0"
val laminarVersion   = "17.2.0"
val tapirVersion     = "1.11.36"
val slickVersion          = "3.6.1"
val postgresVersion       = "42.7.4"
val testcontainersVersion = "0.43.0"
val zioRedisVersion       = "1.1.3"
val zioSchemaVersion      = "1.7.2"
val mongoDriverVersion    = "5.5.1"
val zioRsInteropVersion   = "2.0.2"
val cassandraDriverVersion = "4.17.0"
val neo4jDriverVersion     = "5.28.5"
val clickhouseJdbcVersion  = "0.9.0"
val zioJdbcVersion         = "0.1.2"
val gatlingVersion         = "3.13.5"

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

// Backend-agnostic persistence interface. Defines GameRepository and
// LobbyRepository traits + an in-memory impl that's the dev/test default.
// All real backends (Postgres, Mongo, Redis, Cassandra) live in their own
// downstream modules and depend on this one.
lazy val persistenceApi = project
  .in(file("persistence/api"))
  .dependsOn(domain.jvm)
  .settings(commonSettings)
  .settings(
    name := "pichess-persistence-api",
  )

// Aggregator that knows how to wire every backend impl into a working
// `GameRepository` / `LobbyRepository` layer based on `BackendConfig`. The
// service Mains depend on just this one module instead of every backend
// module + the cache decorator individually.
lazy val persistenceRuntime = project
  .in(file("persistence/runtime"))
  .dependsOn(
    domain.jvm,
    persistenceApi,
    persistencePostgres,
    persistenceRedis,
    persistenceMongo,
    persistenceCassandra,
    persistenceCache
  )
  .settings(commonSettings)
  .settings(
    name := "pichess-persistence-runtime",
    // Layer-selection logic is exercised end-to-end in service-Main tests
    // and the contract module; not unit-coverable in isolation since each
    // branch needs the matching DB to be reachable.
    coverageEnabled := false,
  )

// Cache decorator: wraps any primary GameRepository/LobbyRepository with a
// Redis-backed front cache. Same pattern Phase 1's plan called out — flip
// PICHESS_CACHE=redis and the decorator slots in via ZLayer composition,
// no impl change needed at the call sites.
lazy val persistenceCache = project
  .in(file("persistence/cache"))
  .dependsOn(domain.jvm, persistenceApi, persistenceRedis)
  .settings(commonSettings)
  .settings(
    name := "pichess-persistence-cache",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-redis"           % zioRedisVersion,
      "dev.zio" %% "zio-schema"          % zioSchemaVersion,
      "dev.zio" %% "zio-schema-protobuf" % zioSchemaVersion,
      "dev.zio" %% "zio-json"            % zioJsonVersion,
    ),
  )

// Cassandra backend: DataStax Java driver wrapped via ZIO.fromCompletionStage.
// (cassandra4io has no Scala 3 build, so we go straight to the Java API.)
lazy val persistenceCassandra = project
  .in(file("persistence/cassandra"))
  .dependsOn(domain.jvm, codec, persistenceApi)
  .settings(commonSettings)
  .settings(
    name := "pichess-persistence-cassandra",
    libraryDependencies ++= Seq(
      "com.datastax.oss" % "java-driver-core" % cassandraDriverVersion,
    ),
    coverageEnabled := false,
  )

// MongoDB backend: official Java reactive-streams driver wrapped via
// zio-interop-reactivestreams. (mongo-scala-driver has no Scala 3 build, so
// we go straight to the Java API + a thin ZIO bridge.)
lazy val persistenceMongo = project
  .in(file("persistence/mongo"))
  .dependsOn(domain.jvm, codec, persistenceApi)
  .settings(commonSettings)
  .settings(
    name := "pichess-persistence-mongo",
    libraryDependencies ++= Seq(
      "org.mongodb" %  "mongodb-driver-reactivestreams" % mongoDriverVersion,
      "dev.zio"     %% "zio-interop-reactivestreams"    % zioRsInteropVersion,
      "dev.zio"     %% "zio-json"                       % zioJsonVersion,
    ),
    coverageEnabled := false,
  )

// Redis backend: native zio-redis. Same impl is reused by persistence-cache
// as the cache backing store, so the trait we implement here is the
// boundary the decorator hits.
lazy val persistenceRedis = project
  .in(file("persistence/redis"))
  .dependsOn(domain.jvm, codec, persistenceApi)
  .settings(commonSettings)
  .settings(
    name := "pichess-persistence-redis",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-redis"            % zioRedisVersion,
      "dev.zio" %% "zio-schema"           % zioSchemaVersion,
      "dev.zio" %% "zio-schema-protobuf"  % zioSchemaVersion,
      "dev.zio" %% "zio-json"             % zioJsonVersion,
    ),
    // Real-Redis tests live in persistence-contract via Testcontainers.
    coverageEnabled := false,
  )

// Shared zio-test contract suites that every backend impl must satisfy. Each
// concrete subclass provides a `ZLayer[Any, Throwable, GameRepository]` —
// usually built around a Testcontainers DB instance — and the contract suite
// runs the same set of save/load/delete invariants against it. A backend
// failing the contract is, by definition, not a drop-in replacement.
//
// Testcontainers needs Docker available at test time; CI gates on it.
lazy val persistenceContract = project
  .in(file("persistence/contract"))
  .dependsOn(
    domain.jvm,
    persistenceApi,
    persistencePostgres,
    persistenceRedis,
    persistenceMongo,
    persistenceCassandra
  )
  .settings(commonSettings)
  .settings(
    name := "pichess-persistence-contract",
    libraryDependencies ++= Seq(
      "com.dimafeng" %% "testcontainers-scala-postgresql" % testcontainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-mongodb"    % testcontainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-cassandra"  % testcontainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-core"       % testcontainersVersion % Test,
    ),
    coverageEnabled := false,
  )

// PostgreSQL backend: Slick 3.6 + a tiny inline Future->ZIO bridge.
// (The community zio-slick-interop only publishes for Scala 2.13; the bridge
// it would have provided is a few lines of `ZIO.fromFuture` so we inline it
// here rather than block on a Scala 3 fork.)
lazy val persistencePostgres = project
  .in(file("persistence/postgres"))
  .dependsOn(domain.jvm, codec, persistenceApi)
  .settings(commonSettings)
  .settings(
    name := "pichess-persistence-postgres",
    libraryDependencies ++= Seq(
      "com.typesafe.slick" %% "slick"          % slickVersion,
      "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
      "org.postgresql"     %  "postgresql"     % postgresVersion,
    ),
    // Real-DB integration tests live in persistence-contract and run via
    // Testcontainers; the impls in this module are exercised end-to-end there.
    // Coverage measured at the contract module, not here.
    coverageEnabled := false,
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
  .dependsOn(
    domain.jvm,
    repositoryApi,
    codec,
    events,
    persistenceApi,
    persistenceRuntime
  )
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
  .dependsOn(
    domain.jvm,
    rules,
    codec,
    events,
    proto,
    persistenceApi,
    persistenceRuntime
  )
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

// Supplementary projection: builds a graph of opening positions by
// consuming chess.game-events and walking each MoveMade into Neo4j as a
// (Position{fen})-[:MOVE{san,count}]->(Position{fen}) edge. Read-only from
// the perspective of game state — never serves the primary GameRepository.
lazy val openingService = project
  .in(file("opening-service"))
  .dependsOn(domain.jvm, codec, events)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "pichess-opening-service",
    Compile / mainClass := Some("chess.opening.OpeningMain"),
    libraryDependencies ++= Seq(
      "dev.zio"          %% "zio-kafka"        % zioKafkaVersion,
      "org.neo4j.driver" %  "neo4j-java-driver" % neo4jDriverVersion,
    ),
    Docker / packageName := "pichess-opening-service",
    Docker / version     := "latest",
    dockerBaseImage      := "eclipse-temurin:23-jre",
    dockerUpdateLatest   := true,
    Docker / dockerGroupLayers := pichessLayerGrouping,
    coverageExcludedFiles :=
      ".*OpeningMain.*;.*Kafka.*;.*Neo4j.*",
  )

// Supplementary projection: ClickHouse OLAP store fed by chess.game-events.
// Holds an append-only `move_events` table plus aggregate views; serves
// canonical aggregate queries over a small REST surface so the future
// admin panel can call it without speaking JDBC.
lazy val analyticsService = project
  .in(file("analytics-service"))
  .dependsOn(domain.jvm, codec, events)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "pichess-analytics-service",
    Compile / mainClass := Some("chess.analytics.AnalyticsMain"),
    libraryDependencies ++= Seq(
      "dev.zio"                     %% "zio-kafka"             % zioKafkaVersion,
      "dev.zio"                     %% "zio-jdbc"              % zioJdbcVersion,
      "dev.zio"                     %% "zio-http"              % zioHttpVersion,
      "dev.zio"                     %% "zio-json"              % zioJsonVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-core"            % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-zio"        % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server" % tapirVersion,
      "com.clickhouse"              %  "clickhouse-jdbc"       % clickhouseJdbcVersion,
    ),
    Docker / packageName := "pichess-analytics-service",
    Docker / version     := "latest",
    dockerBaseImage      := "eclipse-temurin:23-jre",
    dockerExposedPorts   := Seq(8093),
    dockerUpdateLatest   := true,
    Docker / dockerGroupLayers := pichessLayerGrouping,
    // DB-touching files (projection, service, schema, JSON codecs, HTTP
    // wiring) are exercised end-to-end against a live ClickHouse, not as
    // unit tests. Pure logic in AnalyticsEventMapping IS unit-tested.
    coverageExcludedFiles :=
      ".*AnalyticsMain.*;.*Kafka.*;.*ClickHouse.*;.*AnalyticsServer.*;" +
        ".*AnalyticsEndpoints.*;.*AnalyticsProjection.*;.*AnalyticsService.*;" +
        ".*AnalyticsJson.*;.*AnalyticsSchema.*",
  )

// New microservice for lobby management. REST-only on :8092 — no gRPC, no
// Kafka in Phase 1 (lobby events come later, see plan §"Future scope"). Uses
// `LobbyRepository` from persistence-api so it inherits the same backend
// swap as game state.
lazy val lobbyService = project
  .in(file("lobby-service"))
  .dependsOn(
    domain.jvm,
    api.jvm,
    persistenceApi,
    persistenceRuntime
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "pichess-lobby-service",
    Compile / mainClass := Some("chess.lobby.LobbyMain"),
    libraryDependencies ++= Seq(
      "dev.zio"                     %% "zio-http"              % zioHttpVersion,
      "dev.zio"                     %% "zio-json"              % zioJsonVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-core"            % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-zio"        % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server" % tapirVersion,
      // Outbound HTTP to the gateway (internal /players hand-off).
      "com.softwaremill.sttp.tapir"   %% "tapir-sttp-client" % tapirVersion,
      "com.softwaremill.sttp.client3" %% "zio"               % "3.11.0",
    ),
    Docker / packageName := "pichess-lobby-service",
    Docker / version     := "latest",
    dockerBaseImage      := "eclipse-temurin:23-jre",
    dockerExposedPorts   := Seq(8092),
    dockerUpdateLatest   := true,
    Docker / dockerGroupLayers := pichessLayerGrouping,
    // HTTP wiring (Main, server, endpoint defs) is exercised by docker-compose
    // smoke tests, not unit coverage. LobbyService logic is covered here.
    coverageExcludedFiles :=
      ".*LobbyMain.*;.*LobbyServer.*;.*LobbyEndpoints.*;.*LobbyJson.*",
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

// Stress / load tests via Gatling. Hits the gateway's HTTP surface; doesn't
// know which backend is wired in — that's the point. Run the same scenario
// with PICHESS_BACKEND=postgres / mongo / redis / cassandra to produce
// comparative throughput numbers under `gatling/target/gatling/`.
//
// Run with:  sbt 'gatling/Gatling/test'      (all simulations)
//            sbt 'gatling/Gatling/testOnly chess.gatling.GameSimulation'
lazy val gatling = project
  .in(file("gatling"))
  .enablePlugins(GatlingPlugin)
  .settings(
    name := "pichess-gatling",
    scalaVersion := "3.8.2",
    libraryDependencies ++= Seq(
      "io.gatling.highcharts" % "gatling-charts-highcharts" % gatlingVersion % Test,
      "io.gatling"            % "gatling-test-framework"    % gatlingVersion % Test,
    ),
    coverageEnabled := false,
  )

// TUI is currently a parser-only library. Runtime (stdin loop + REST client
// Container-friendly text UI. Reads commands from stdin, calls the gateway
// over HTTP using the typed `chess.api.Endpoints` (same Tapir contract the
// web-ui uses), and renders results back to stdout. Runs in its own
// container so a TUI crash can't kill the gateway and so spinning up
// multiple instances (e.g. bots-vs-bots) doesn't require gateway changes.
lazy val tui = project
  .in(file("tui"))
  .dependsOn(domain.jvm, codec, api.jvm)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "pichess-tui",
    Compile / mainClass := Some("chess.tui.TuiMain"),
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir"   %% "tapir-sttp-client" % tapirVersion,
      "com.softwaremill.sttp.client3" %% "zio"               % "3.11.0",
      // Lobby JSON wire types are decoded in TuiClient — gateway proxies
      // /lobbies/* to the lobby-service so we don't need a Tapir contract,
      // just a small zio-json codec mirror of the fields we read.
      "dev.zio"                       %% "zio-json"          % zioJsonVersion,
    ),
    Docker / packageName := "pichess-tui",
    Docker / version     := "latest",
    dockerBaseImage      := "eclipse-temurin:23-jre",
    dockerUpdateLatest   := true,
    Docker / dockerGroupLayers := pichessLayerGrouping,
    coverageExcludedFiles :=
      ".*TuiMain.*;.*TuiClient.*;.*TuiEventStream.*",
  )

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
    persistenceApi,
    persistencePostgres,
    persistenceRedis,
    persistenceMongo,
    persistenceCassandra,
    persistenceCache,
    persistenceRuntime,
    persistenceContract,
    events,
    proto,
    repository,
    gameService,
    lobbyService,
    openingService,
    analyticsService,
    gateway,
    tui,
    webUi,
    gatling,
  )
  .settings(
    name := "pichess",
    // `sbt run` at the root has no single Main since the app monolith was
    // split into separate gateway / gameService / repository services. Use
    // `sbt <svc>/run` for individual services or `docker compose up` for the
    // integrated stack.
  )

// Build all seven service images into the local Docker daemon. Run this
// once before `docker compose up` (and after every service-side change).
// Listed in dependency order — domain/persistence libs are pulled in
// transitively — so a single `sbt dockerBuildAll` is enough.
addCommandAlias(
  "dockerBuildAll",
  Seq(
    "gameService/Docker/publishLocal",
    "repository/Docker/publishLocal",
    "lobbyService/Docker/publishLocal",
    "openingService/Docker/publishLocal",
    "analyticsService/Docker/publishLocal",
    "gateway/Docker/publishLocal",
    "tui/Docker/publishLocal"
  ).mkString(";", ";", "")
)
