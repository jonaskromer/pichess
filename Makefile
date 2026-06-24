# pichess developer entrypoints. Replaces the older scripts/dev-up.sh —
# every target is documented inline; run `make` (or `make help`) to list them.
#
# Service names below match the docker-compose service names (kebab-case).
# The corresponding sbt subproject names are camelCase, hence the per-service
# build- targets that bridge between the two.

.DEFAULT_GOAL := help

# --- Env-file layering ----------------------------------------------------
#
# `.env` ships in the repo with dev-friendly defaults (e.g. PICHESS_DEV=true).
# `.env.local` is per-developer and gitignored — override anything you want
# without touching the committed file. `-include` is silent when the file
# doesn't exist, so a fresh checkout still works. `export` puts every var
# read here into the recipe shell's environment, where docker compose picks
# them up (and any inline `VAR=value` on a stack-* recipe wins as usual).
-include .env
-include .env.local
export

# --- Stack-wide targets ---------------------------------------------------

.PHONY: help
help: ## Show this target list
	@grep -hE '^[a-zA-Z0-9_-]+:[^:]*?##' $(MAKEFILE_LIST) | sort | \
	  awk -F ':.*?##' '{printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

.PHONY: build
build: tailwind-build ## Build all service images via sbt dockerBuildAll
	sbt dockerBuildAll

# --- Tailwind CSS pipeline ------------------------------------------------
# We use the v4 standalone binary so the project doesn't grow a Node
# toolchain just to build CSS. The binary is fetched once into ./bin/
# (gitignored); CI / a fresh checkout just runs `make tailwind-install`.

TAILWIND_VERSION := v4.3.0
TAILWIND_BINARY  := bin/tailwindcss
TAILWIND_INPUT   := gateway/src/main/tailwind/input.css
TAILWIND_OUTPUT  := gateway/src/main/resources/web/style.css

# Detect host OS / arch for the standalone binary download. macOS ships
# both intel and arm64; Linux mostly x64 / arm64. The CLI release page
# uses the same naming convention either way.
TAILWIND_OS_RAW   := $(shell uname -s)
TAILWIND_ARCH_RAW := $(shell uname -m)
ifeq ($(TAILWIND_OS_RAW),Darwin)
  TAILWIND_OS := macos
else
  TAILWIND_OS := linux
endif
ifeq ($(TAILWIND_ARCH_RAW),x86_64)
  TAILWIND_ARCH := x64
else ifeq ($(TAILWIND_ARCH_RAW),aarch64)
  TAILWIND_ARCH := arm64
else ifeq ($(TAILWIND_ARCH_RAW),arm64)
  TAILWIND_ARCH := arm64
else
  TAILWIND_ARCH := $(TAILWIND_ARCH_RAW)
endif

.PHONY: tailwind-install
tailwind-install: ## Download the Tailwind v4 standalone CLI into bin/
	@mkdir -p bin
	@if [ ! -x $(TAILWIND_BINARY) ]; then \
	  echo "fetching tailwindcss $(TAILWIND_VERSION) for $(TAILWIND_OS)-$(TAILWIND_ARCH)"; \
	  curl -fsSL -o $(TAILWIND_BINARY) \
	    https://github.com/tailwindlabs/tailwindcss/releases/download/$(TAILWIND_VERSION)/tailwindcss-$(TAILWIND_OS)-$(TAILWIND_ARCH); \
	  chmod +x $(TAILWIND_BINARY); \
	fi

.PHONY: tailwind-build
tailwind-build: tailwind-install ## Generate the production stylesheet (one-shot, minified)
	$(TAILWIND_BINARY) -i $(TAILWIND_INPUT) -o $(TAILWIND_OUTPUT) --minify

.PHONY: tailwind-watch
tailwind-watch: tailwind-install ## Watch Scala sources + input.css and rebuild on change
	$(TAILWIND_BINARY) -i $(TAILWIND_INPUT) -o $(TAILWIND_OUTPUT) --watch

.PHONY: up
up: ## Start the integrated stack (assumes images already built)
	docker compose up -d

.PHONY: down
down: ## Stop the stack — containers removed, volumes preserved
	docker compose down

.PHONY: clean
clean: ## Stop the stack AND wipe volumes (resets every DB to empty)
	docker compose down -v

.PHONY: ps
ps: ## List running containers in the stack
	docker compose ps

.PHONY: logs
logs: ## Tail logs for every service in the stack
	docker compose logs -f

# --- Stack switcher -------------------------------------------------------
#
# Performance / profiling setups want exactly one persistence backend
# active at a time. Compose profiles (`profiles: ["postgres"]` etc.) on
# each backend service let us bring up only the wanted stack. The
# Makefile passes both `--profile <name>` to compose AND
# `PICHESS_BACKEND=<name>` as an env var so the services pick the
# matching impl at runtime.
#
# Optional projection stacks (`opening`, `analytics`) can be layered on:
#
#   make stack-postgres EXTRA=opening,analytics
#
# Last-selected profile is persisted to .pichess-stack so `make
# stack-status` and `make stack-restart` know what's "current". Data
# loss is acceptable — these flows are dev-only.

STACK_STATE_FILE := .pichess-stack
EXTRA            ?=
# All known profiles. `docker compose down` without `--profile` only
# stops unprofiled services, so we enumerate every profile here when
# tearing down to make sure nothing left over from a previous run
# survives a stack switch.
ALL_PROFILES := --profile postgres --profile mongo --profile cassandra \
                --profile redis --profile opening --profile analytics \
                --profile tui --profile obs --profile k6

# Convert "opening,analytics" → "--profile opening --profile analytics"
# (empty string when EXTRA is unset). `empty :=` is the standard Make
# idiom for capturing a single space between two empty values.
empty :=
space := $(empty) $(empty)
comma := ,
EXTRA_PROFILES = $(foreach p,$(subst $(comma),$(space),$(EXTRA)),--profile $(p))

# Set PICHESS_KAFKA only when the user's EXTRA includes `opening` or
# `analytics` — those are the profiles that bring Kafka up. Otherwise
# leave it empty so game-service falls back to its in-memory event
# producer (kafka:9092 wouldn't resolve and the service would crash).
KAFKA_FOR_EXTRA = $(if $(findstring opening,$(EXTRA))$(findstring analytics,$(EXTRA)),kafka:9092,)

# Default cache mode. Matches the BackendConfig default
# (`PICHESS_CACHE` absent → CacheBackend.Redis). Override on the
# make-line to opt out: `PICHESS_CACHE=none make stack-postgres`.
PICHESS_CACHE ?= redis

# When PICHESS_CACHE=redis, bring up the redis container too so the
# cache decorator has something to talk to. Redundant when the primary
# is already redis (`stack-redis`), but `docker compose` accepts the
# repeated `--profile redis` without complaint.
CACHE_PROFILE = $(if $(filter redis,$(PICHESS_CACHE)),--profile redis,)

define _stack_up
	@mkdir -p $(dir $(STACK_STATE_FILE)) || true
	@echo "PICHESS_BACKEND=$(1) PICHESS_CACHE=$(PICHESS_CACHE) PICHESS_EXTRAS=$(EXTRA)" > $(STACK_STATE_FILE)
	docker compose $(ALL_PROFILES) down 2>/dev/null || true
	PICHESS_BACKEND=$(1) PICHESS_CACHE=$(PICHESS_CACHE) PICHESS_EXTRAS=$(EXTRA) \
	  PICHESS_KAFKA="$(KAFKA_FOR_EXTRA)" \
	  docker compose --profile $(1) $(CACHE_PROFILE) $(EXTRA_PROFILES) up -d
endef

.PHONY: stack-postgres
stack-postgres: ## Start the stack with PICHESS_BACKEND=postgres (default cache: redis; opt out with PICHESS_CACHE=none)
	$(call _stack_up,postgres)

.PHONY: stack-mongo
stack-mongo: ## Start the stack with PICHESS_BACKEND=mongo (default cache: redis; opt out with PICHESS_CACHE=none)
	$(call _stack_up,mongo)

.PHONY: stack-cassandra
stack-cassandra: ## Start the stack with PICHESS_BACKEND=cassandra (default cache: redis; opt out with PICHESS_CACHE=none)
	$(call _stack_up,cassandra)

.PHONY: stack-redis
stack-redis: PICHESS_CACHE := none
stack-redis: ## Start the stack with PICHESS_BACKEND=redis (no separate cache — primary already redis)
	$(call _stack_up,redis)

.PHONY: stack-inmemory
stack-inmemory: ## Start the stack with no DB (PICHESS_BACKEND=inmemory, no cache)
	@mkdir -p $(dir $(STACK_STATE_FILE)) || true
	@echo "PICHESS_BACKEND=inmemory PICHESS_CACHE=none PICHESS_EXTRAS=$(EXTRA)" > $(STACK_STATE_FILE)
	docker compose $(ALL_PROFILES) down 2>/dev/null || true
	PICHESS_BACKEND=inmemory PICHESS_CACHE=none PICHESS_EXTRAS=$(EXTRA) \
	  PICHESS_KAFKA="$(KAFKA_FOR_EXTRA)" \
	  docker compose $(EXTRA_PROFILES) up -d

.PHONY: stack-down
stack-down: ## Stop the active stack and clear the state file
	docker compose $(ALL_PROFILES) down
	@rm -f $(STACK_STATE_FILE)

.PHONY: stack-status
stack-status: ## Show the active stack profile + running containers
	@if [ -f $(STACK_STATE_FILE) ]; then \
	  echo "Active stack:"; cat $(STACK_STATE_FILE); \
	else \
	  echo "No stack selected (run 'make stack-postgres' etc.)"; \
	fi
	@echo ""
	@echo "Running containers:"
	@docker compose ps

# --- Profile addons -------------------------------------------------------
#
# Additive compose profiles. Each `<name>-up` brings the profile's
# services up alongside whatever stack is already running; `<name>-down`
# stops only that profile's containers without touching the rest.
#
# Equivalent to `make stack-<backend> EXTRA=<name>` but doesn't tear the
# current stack down — useful for layering observability or a
# projection onto an already-running dev session.
#
# Caveats:
#   * `make opening` / `make analytics` bring up Kafka + the consumer
#     service(s), but game-service only publishes if its KAFKA_BOOTSTRAP_SERVERS
#     env was set at start time. Use `make stack-<bk> EXTRA=opening` (or
#     `analytics`) for full integration; the standalone targets here are
#     for "bring up the consumers against an idle producer" cases.
#   * `make obs` is fully self-contained — Prometheus / Grafana / Jaeger
#     have no service dependencies and can come up at any time.

.PHONY: obs
obs: ## Bring up Prometheus + Grafana + Jaeger alongside the running stack
	docker compose --profile obs up -d prometheus grafana jaeger

.PHONY: obs-down
obs-down: ## Stop the obs containers (Prometheus / Grafana / Jaeger) only
	docker compose stop prometheus grafana jaeger 2>/dev/null || true
	docker compose rm -f prometheus grafana jaeger 2>/dev/null || true

.PHONY: obs-status
obs-status: ## Show which obs services are up + their URLs
	@printf '%-12s %-30s %s\n' "Service" "URL" "State"
	@for row in "prometheus|http://localhost:9090" \
	            "grafana|http://localhost:3000" \
	            "jaeger|http://localhost:16686"; do \
	  svc=$${row%%|*}; url=$${row##*|}; \
	  if [ -n "$$(docker compose ps -q $$svc 2>/dev/null)" ]; then \
	    state=up; \
	  else \
	    state=down; \
	  fi; \
	  printf '%-12s %-30s %s\n' "$$svc" "$$url" "$$state"; \
	done

.PHONY: grafana
grafana: ## Open Grafana in the default browser (needs `make obs` first)
	@open http://localhost:3000 2>/dev/null || \
	  xdg-open http://localhost:3000 2>/dev/null || \
	  echo "Open http://localhost:3000 in your browser"

.PHONY: prometheus
prometheus: ## Open Prometheus in the default browser (needs `make obs` first)
	@open http://localhost:9090 2>/dev/null || \
	  xdg-open http://localhost:9090 2>/dev/null || \
	  echo "Open http://localhost:9090 in your browser"

.PHONY: jaeger
jaeger: ## Open Jaeger UI in the default browser (needs `make obs` first)
	@open http://localhost:16686 2>/dev/null || \
	  xdg-open http://localhost:16686 2>/dev/null || \
	  echo "Open http://localhost:16686 in your browser"

.PHONY: opening
opening: ## Bring up Kafka + opening-service + Neo4j alongside the running stack
	PICHESS_KAFKA=kafka:9092 docker compose --profile opening up -d kafka opening-service neo4j

.PHONY: opening-down
opening-down: ## Stop the opening projection (opening-service + Neo4j; keeps Kafka if analytics also uses it)
	docker compose stop opening-service neo4j 2>/dev/null || true
	docker compose rm -f opening-service neo4j 2>/dev/null || true

.PHONY: analytics
analytics: ## Bring up Kafka + analytics-service + ClickHouse alongside the running stack
	PICHESS_KAFKA=kafka:9092 docker compose --profile analytics up -d kafka analytics-service clickhouse

.PHONY: analytics-down
analytics-down: ## Stop the analytics projection (analytics-service + ClickHouse)
	docker compose stop analytics-service clickhouse 2>/dev/null || true
	docker compose rm -f analytics-service clickhouse 2>/dev/null || true

.PHONY: kafka-down
kafka-down: ## Stop Kafka (only safe when neither opening nor analytics is up)
	docker compose stop kafka 2>/dev/null || true
	docker compose rm -f kafka 2>/dev/null || true

# --- Dev report bake-in ---------------------------------------------------
#
# The /dev/test/coverage and /dev/test/performance pages iframe static
# HTML reports baked into the gateway image. These targets generate
# fresh reports + copy them into gateway resources. They're explicit
# (NOT chained off `make build-gateway`) because both are slow — the
# user runs them when they want to refresh the bundled artifacts, then
# rebuilds the gateway image to ship them.

COVERAGE_SRC := target/scala-3.8.2/scoverage-report
COVERAGE_DST := gateway/src/main/resources/dev/coverage/report

GATLING_SRC  := gatling/target/gatling
GATLING_DST  := gateway/src/main/resources/dev/performance/report

.PHONY: coverage-build
coverage-build: ## Run coverage + bake the aggregated HTML report into the gateway resources
	sbt clean coverage test coverageReport coverageAggregate
	@mkdir -p $(COVERAGE_DST)
	@rm -rf $(COVERAGE_DST)/*
	@if [ -d $(COVERAGE_SRC) ]; then \
	  cp -R $(COVERAGE_SRC)/* $(COVERAGE_DST)/; \
	  echo "coverage report copied → $(COVERAGE_DST)/"; \
	else \
	  echo "warning: $(COVERAGE_SRC) not found (did sbt coverageAggregate succeed?)"; \
	  exit 1; \
	fi

.PHONY: coverage-clean
coverage-clean: ## Remove the baked coverage report from gateway resources (frees the gateway test that asserts /dev/coverage/report 404s when no report is baked)
	@if [ -d $(COVERAGE_DST) ]; then \
	  rm -rf $(COVERAGE_DST)/*; \
	  echo "removed baked coverage report from $(COVERAGE_DST)/"; \
	else \
	  echo "$(COVERAGE_DST)/ does not exist — nothing to clean."; \
	fi

.PHONY: scalafix-check
scalafix-check: ## Run scalafix in check mode — fail on any rule violation, no edits
	sbt 'scalafixAll --check'

.PHONY: scalafix-fix
scalafix-fix: ## Apply scalafix's auto-fixes (RemoveUnused / OrganizeImports). DisableSyntax violations still surface as errors — review and fix by hand.
	sbt scalafixAll

.PHONY: gatling-build
gatling-build: ## Run gatling + bake the latest report into the gateway resources
	sbt 'gatling/Gatling/test'
	@mkdir -p $(GATLING_DST)
	@rm -rf $(GATLING_DST)/*
	@latest=$$(ls -dt $(GATLING_SRC)/*/ 2>/dev/null | head -1); \
	if [ -z "$$latest" ]; then \
	  echo "warning: no gatling run found under $(GATLING_SRC)/"; \
	  exit 1; \
	else \
	  cp -R "$$latest"* $(GATLING_DST)/; \
	  echo "gatling report copied from $$latest → $(GATLING_DST)/"; \
	fi

# --- Performance test suite ------------------------------------------------
#
# The piChess perf stack maps to six layers documented in
# docs/performance.md. The make targets below let you run each layer
# (or surface) standalone, plus `perf-all` for the full sweep.
#
#   Layer 1   — Gatling cross-backend load tests       → make perf
#   Layer 1b  — k6 (browser / kafka / gRPC surfaces)    → make k6-browser, etc.
#   Layer 2   — JMH microbenchmarks                     → make bench
#   Layer 4   — async-profiler (attach to live service) → make profile-async-cpu SERVICE=…
#
#   Full suite (Layers 1 + 1b + 2)                       → make perf-all
#   Summary regenerate                                   → make perf-summary
#   Bake artifacts into the dev page                     → make perf-bake
#
# Layers 3 / 5 / 6 (zio-profiling, Prometheus+Grafana, OTel+Jaeger) are
# environment-driven rather than target-driven — see docs/performance.md.

PERF_REPORTS_DIR := perf-reports

# JMH iteration / fork counts. Switchable for the dev vs. publication
# tradeoff:
#   default          3 warmup + 3 measurement × 1 fork  — current behaviour
#   BENCH_QUICK=true 1 warmup + 1 measurement × 1 fork  — ~1/3 runtime, noisy
#   BENCH_THOROUGH=true 5 warmup + 10 measurement × 2 forks — ~5× runtime,
#                       publication-grade confidence intervals
ifeq ($(BENCH_QUICK),true)
JMH_FLAGS := -i 1 -wi 1 -f1 -r 1s -w 1s
else ifeq ($(BENCH_THOROUGH),true)
JMH_FLAGS := -i 10 -wi 5 -f2 -r 1s -w 1s
else
JMH_FLAGS := -i 3 -wi 3 -f1 -r 1s -w 1s
endif

# Subset filters — each bench-<scope> target runs the listed JMH
# classes. Keeping these in one place so adding a new bench class is
# a single-line change.
BENCH_CODEC_CLASSES   := chess.bench.FenParserBenchmark chess.bench.FenSerializerBenchmark chess.bench.SanRoundTripBenchmark chess.bench.PgnParserBenchmark chess.bench.ZobristHashBenchmark
BENCH_RULES_CLASSES   := chess.bench.MoveValidatorBenchmark chess.bench.RayWalkBenchmark chess.bench.GameApplyMoveBenchmark
BENCH_BOT_CLASSES     := chess.bench.SearchBenchmark chess.bench.TexelTunerBenchmark chess.bench.SelfPlayBenchmark
# `bench-persistence` and `bench-wire` slots are stubs at this point —
# the bench classes land in Phase D alongside the matching optimisations.
# Running them today errors with "No matching benchmarks" until that work
# lands; the targets are kept here so wiring is one-line when it does.
BENCH_PERSISTENCE_CLASSES := chess.bench.persistence
BENCH_WIRE_CLASSES        := chess.bench.wire

.PHONY: bench
bench: ## Run the full JMH suite (= bench-codec + bench-rules today). Vars: BENCH_QUICK, BENCH_THOROUGH
	@mkdir -p $(PERF_REPORTS_DIR)
	@ts=$$(date -u +%Y%m%dT%H%M%SZ); \
	out="$$PWD/$(PERF_REPORTS_DIR)/bench-$$ts.json"; \
	sbt "bench/Jmh/run $(JMH_FLAGS) -rf json -rff $$out"; \
	echo "JMH results → $$out"

.PHONY: bench-codec
bench-codec: ## Codec benches — FEN / SAN / PGN / Zobrist. Pure code, no stack needed.
	@mkdir -p $(PERF_REPORTS_DIR)
	@ts=$$(date -u +%Y%m%dT%H%M%SZ); \
	out="$$PWD/$(PERF_REPORTS_DIR)/bench-codec-$$ts.json"; \
	sbt "bench/Jmh/run $(JMH_FLAGS) -rf json -rff $$out $(BENCH_CODEC_CLASSES)"; \
	echo "codec bench results → $$out"

.PHONY: bench-rules
bench-rules: ## Rules benches — MoveValidator / Ray / GameApplyMove. Pure code, no stack needed.
	@mkdir -p $(PERF_REPORTS_DIR)
	@ts=$$(date -u +%Y%m%dT%H%M%SZ); \
	out="$$PWD/$(PERF_REPORTS_DIR)/bench-rules-$$ts.json"; \
	sbt "bench/Jmh/run $(JMH_FLAGS) -rf json -rff $$out $(BENCH_RULES_CLASSES)"; \
	echo "rules bench results → $$out"

.PHONY: bench-bot
bench-bot: ## Bot benches — Search (α-β + TT) + TexelTuner training loop. Pure code, no stack needed.
	@mkdir -p $(PERF_REPORTS_DIR)
	@ts=$$(date -u +%Y%m%dT%H%M%SZ); \
	out="$$PWD/$(PERF_REPORTS_DIR)/bench-bot-$$ts.json"; \
	sbt "bench/Jmh/run $(JMH_FLAGS) -rf json -rff $$out $(BENCH_BOT_CLASSES)"; \
	echo "bot bench results → $$out"

.PHONY: nnue-data
nnue-data: ## Build the shared Lichess-eval dataset (download 17 shards once → one depth-filtered gzipped TSV that the NNUE/HCE/policy trainers all read). Vars: MIN_DEPTH (24), MULTIPV (4), OUT
	@.venv-nnue/bin/python nnue-train/extract_shards.py \
		--out $(or $(OUT),nnue-train/data/lichess-eval.tsv.gz) \
		--min-depth $(or $(MIN_DEPTH),24) --multipv $(or $(MULTIPV),4)

.PHONY: hce-distill
hce-distill: ## Re-tune the HCE weights by distilling Stockfish evals from the shared dataset → weights/vN.json (roadmap 7b). Needs `make nnue-data` first. Vars: TSV, OUT
	sbt "botTrain/runMain chess.bot.train.SfDistillMain $(or $(TSV),nnue-train/data/lichess-eval.tsv.gz) $(or $(OUT),bot-engine/src/main/resources/weights/v9.json)"

.PHONY: policy-prior
policy-prior: ## Build the SF-distilled move-ordering priors → /policy-prior.bin (roadmap 4b). Needs `make nnue-data` first. Vars: TSV
	sbt "botTrain/runMain chess.bot.train.PolicyPriorMain $(or $(TSV),nnue-train/data/lichess-eval.tsv.gz)"

.PHONY: nnue-retrain
nnue-retrain: ## Retrain the NNUE from the shared TSV with depth-weighting → nnue-v1.bin (roadmap 6b). Needs `make nnue-data` first. Vars: TSV, OUT, EPOCHS, DEPTH_NORM
	.venv-nnue/bin/python nnue-train/train_incremental.py \
		--out $(or $(OUT),bot-engine/src/main/resources/nnue-v1.bin) \
		--tsv $(or $(TSV),nnue-train/data/lichess-eval.tsv.gz) \
		--epochs $(or $(EPOCHS),3) --depth-norm $(or $(DEPTH_NORM),40)

.PHONY: ab-sweep
ab-sweep: ## Re-A/B off-by-default search flags at the live budget → tagged keep/provisional table (roadmap #4). HOURS at default GAMES. Vars: GAMES (200), BUDGET_MS (2000), FLAGS, WEIGHTS, ALPHA
	scripts/ab-sweep.sh

.PHONY: tournament-bot
tournament-bot: ## Connect piChess to a NowChess tournament & play (auto-registers). With NAME set it WAITS, joining as soon as a matching tournament opens. Vars: SERVER (base URL), NAME (name substring) or ID (exact), BOT (piChess), DEPTH. Long-running — wrap in tmux/nohup on a server.
	TOURNAMENT_BASE_URL="$(or $(SERVER),http://141.37.123.132:8086)" \
	TOURNAMENT_BOT_NAME="$(or $(BOT),piChess)" \
	$(if $(ID),TOURNAMENT_ID="$(ID)",) $(if $(NAME),TOURNAMENT_NAME="$(NAME)",) \
	$(if $(DEPTH),TOURNAMENT_MOVE_DEPTH="$(DEPTH)",) \
	sbt "botTournament/runMain chess.bot.tournament.TournamentBotMain"

.PHONY: bench-persistence
bench-persistence: ## Per-backend persistence benches (testcontainer-backed). Phase D stub today.
	@mkdir -p $(PERF_REPORTS_DIR)
	@ts=$$(date -u +%Y%m%dT%H%M%SZ); \
	out="$$PWD/$(PERF_REPORTS_DIR)/bench-persistence-$$ts.json"; \
	sbt "bench/Jmh/run $(JMH_FLAGS) -rf json -rff $$out $(BENCH_PERSISTENCE_CLASSES)"; \
	echo "persistence bench results → $$out"

.PHONY: bench-wire
bench-wire: ## Wire-format benches — BoardStateDto JSON, GameDomainEvent JSON, protobuf. Phase D stub today.
	@mkdir -p $(PERF_REPORTS_DIR)
	@ts=$$(date -u +%Y%m%dT%H%M%SZ); \
	out="$$PWD/$(PERF_REPORTS_DIR)/bench-wire-$$ts.json"; \
	sbt "bench/Jmh/run $(JMH_FLAGS) -rf json -rff $$out $(BENCH_WIRE_CLASSES)"; \
	echo "wire bench results → $$out"

.PHONY: perf
perf: ## Cross-backend Gatling harness. Vars: BACKENDS, MODE, OBS, PEAK_USERS, …
	scripts/perf-run.sh

.PHONY: db-matrix
db-matrix: ## Persistence experiment — backend×cache×workload matrix. Vars: BACKENDS, WORKLOADS, WARMUP_ITERS, PEAK_USERS, RAMP_SECONDS, HOLD_SECONDS, RATE_PER_SEC
	scripts/db-matrix.sh

.PHONY: perf-report
perf-report: ## Generate performance-test-results.md from a perf-reports/<TS>/ dir (defaults to most recent)
	scripts/perf-report.sh $(RUN_DIR)

.PHONY: perf-summary
perf-summary: ## Rebuild comparison.md for the most recent perf run
	@latest=$$(ls -dt $(PERF_REPORTS_DIR)/*/ 2>/dev/null | head -1); \
	if [ -z "$$latest" ]; then \
	  echo "no runs found under $(PERF_REPORTS_DIR)/"; exit 1; \
	fi; \
	scripts/perf-summary.sh "$$latest"

.PHONY: perf-clean
perf-clean: ## Delete every previous perf-report (runs + loose bench JSON + profiles)
	@if [ ! -d $(PERF_REPORTS_DIR) ]; then \
	  echo "$(PERF_REPORTS_DIR)/ does not exist — nothing to clean."; exit 0; \
	fi; \
	count=$$(find $(PERF_REPORTS_DIR) -mindepth 1 -maxdepth 1 | wc -l | tr -d ' '); \
	if [ "$$count" = "0" ]; then \
	  echo "$(PERF_REPORTS_DIR)/ is already empty."; exit 0; \
	fi; \
	size=$$(du -sh $(PERF_REPORTS_DIR) | cut -f1); \
	echo "removing $$count item(s) ($$size) under $(PERF_REPORTS_DIR)/"; \
	rm -rf $(PERF_REPORTS_DIR); \
	mkdir -p $(PERF_REPORTS_DIR); \
	echo "done."

.PHONY: profile-async-cpu
profile-async-cpu: ## Attach async-profiler (cpu) to SERVICE for DURATION seconds
	@if [ -z "$(SERVICE)" ]; then \
	  echo "usage: make profile-async-cpu SERVICE=<name> [DURATION=60]"; \
	  exit 1; \
	fi
	scripts/profile-async.sh $(SERVICE) $${DURATION:-60} cpu

.PHONY: profile-async-alloc
profile-async-alloc: ## Attach async-profiler (alloc) to SERVICE for DURATION seconds
	@if [ -z "$(SERVICE)" ]; then \
	  echo "usage: make profile-async-alloc SERVICE=<name> [DURATION=60]"; \
	  exit 1; \
	fi
	scripts/profile-async.sh $(SERVICE) $${DURATION:-60} alloc

.PHONY: perf-bake
perf-bake: ## Copy the most recent perf-reports/<ts>/ tree into the gateway resources
	@latest=$$(ls -dt $(PERF_REPORTS_DIR)/*/ 2>/dev/null | head -1); \
	if [ -z "$$latest" ]; then \
	  echo "no perf runs found under $(PERF_REPORTS_DIR)/"; exit 1; \
	fi; \
	mkdir -p $(GATLING_DST); \
	rm -rf $(GATLING_DST)/*; \
	cp -R "$$latest"* $(GATLING_DST)/; \
	echo "perf artifacts baked from $$latest → $(GATLING_DST)/"

# --- k6 (Layer 1b — surfaces Gatling can't reach) -------------------------
#
# k6 lives parallel to gatling/: same output convention
# (perf-reports/<UTC-ts>/k6/<surface>/), separate driver. See k6/README.md
# and docs/performance.md "Layer 1b" for the surface map.

.PHONY: k6-build
k6-build: ## Build the custom k6 image (pinned grafana/k6 with browser)
	docker compose --profile k6 build k6

.PHONY: k6
k6: ## Run k6 surfaces. SURFACES=browser (default) / browser,grpc,kafka / etc. Vars: PICHESS_K6_VUS, PICHESS_K6_DURATION
	SURFACES=$${SURFACES:-browser} scripts/k6-run.sh

.PHONY: k6-browser
k6-browser: ## Run only the k6/browser flow against the gateway UI
	SURFACES=browser scripts/k6-run.sh

.PHONY: k6-kafka
k6-kafka: ## Run only the xk6-kafka direct producer load (requires xk6-kafka build)
	SURFACES=kafka scripts/k6-run.sh

.PHONY: k6-grpc
k6-grpc: ## Run only the native k6 gRPC load against game-service
	SURFACES=grpc scripts/k6-run.sh

# --- Full performance suite -----------------------------------------------

.PHONY: perf-all
perf-all: ## Run the full perf suite — JMH bench + Gatling cross-backend + k6 browser. Honors BACKENDS, MODE, OBS, K6_VUS, K6_DURATION
	scripts/perf-all.sh

.PHONY: stack-restart
stack-restart: ## Re-up the last-selected stack (reads $(STACK_STATE_FILE))
	@if [ ! -f $(STACK_STATE_FILE) ]; then \
	  echo "No previous stack — run a stack-<name> target first."; exit 1; \
	fi
	@set -a; . ./$(STACK_STATE_FILE); set +a; \
	  docker compose down 2>/dev/null || true; \
	  EXTRA_PROF=""; \
	  for p in $$(echo "$$PICHESS_EXTRAS" | tr ',' ' '); do \
	    if [ -n "$$p" ]; then EXTRA_PROF="$$EXTRA_PROF --profile $$p"; fi; \
	  done; \
	  PICHESS_BACKEND=$$PICHESS_BACKEND PICHESS_EXTRAS=$$PICHESS_EXTRAS \
	    docker compose --profile $$PICHESS_BACKEND $$EXTRA_PROF up -d

# --- Per-service rebuild + restart ----------------------------------------
#
# Each `build-X` republishes the image to the local Docker daemon; each
# `dev-X` does that *and* restarts only that container with --no-deps so
# the DBs and other services keep running.

.PHONY: build-gateway
build-gateway: tailwind-build ## Rebuild gateway image
	sbt gateway/Docker/publishLocal

.PHONY: build-game-service
build-game-service: ## Rebuild game-service image
	sbt gameService/Docker/publishLocal

.PHONY: build-repository
build-repository: ## Rebuild repository image
	sbt repository/Docker/publishLocal

.PHONY: build-lobby-service
build-lobby-service: ## Rebuild lobby-service image
	sbt lobbyService/Docker/publishLocal

.PHONY: build-opening-service
build-opening-service: ## Rebuild opening-service image
	sbt openingService/Docker/publishLocal

.PHONY: build-analytics-service
build-analytics-service: ## Rebuild analytics-service image
	sbt analyticsService/Docker/publishLocal

.PHONY: build-tui
build-tui: ## Rebuild tui image
	sbt tui/Docker/publishLocal

.PHONY: dev-gateway
dev-gateway: build-gateway ## Rebuild + restart gateway only
	docker compose up -d --no-deps gateway

.PHONY: dev-game-service
dev-game-service: build-game-service ## Rebuild + restart game-service only
	docker compose up -d --no-deps game-service

.PHONY: dev-repository
dev-repository: build-repository ## Rebuild + restart repository only
	docker compose up -d --no-deps repository

.PHONY: dev-lobby-service
dev-lobby-service: build-lobby-service ## Rebuild + restart lobby-service only
	docker compose up -d --no-deps lobby-service

.PHONY: dev-opening-service
dev-opening-service: build-opening-service ## Rebuild + restart opening-service only
	docker compose up -d --no-deps opening-service

.PHONY: dev-analytics-service
dev-analytics-service: build-analytics-service ## Rebuild + restart analytics-service only
	docker compose up -d --no-deps analytics-service

# --- Shells into running containers ---------------------------------------

.PHONY: shell-gateway
shell-gateway: ## Open a shell in the gateway container
	docker compose exec gateway sh

.PHONY: shell-game-service
shell-game-service: ## Open a shell in the game-service container
	docker compose exec game-service sh

.PHONY: shell-repository
shell-repository: ## Open a shell in the repository container
	docker compose exec repository sh

.PHONY: shell-lobby-service
shell-lobby-service: ## Open a shell in the lobby-service container
	docker compose exec lobby-service sh

.PHONY: shell-opening-service
shell-opening-service: ## Open a shell in the opening-service container
	docker compose exec opening-service sh

.PHONY: shell-analytics-service
shell-analytics-service: ## Open a shell in the analytics-service container
	docker compose exec analytics-service sh

# --- DB consoles ----------------------------------------------------------

.PHONY: psql
psql: ## Open a psql session in the postgres container
	docker compose exec postgres psql -U postgres -d pichess

.PHONY: mongo
mongo: ## Open a mongosh session in the mongodb container
	docker compose exec mongodb mongosh

.PHONY: redis-cli
redis-cli: ## Open a redis-cli session in the redis container
	docker compose exec redis redis-cli

.PHONY: cqlsh
cqlsh: ## Open a cqlsh session in the cassandra container
	docker compose exec cassandra cqlsh

.PHONY: cypher
cypher: ## Open a cypher-shell session in the neo4j container
	docker compose exec neo4j cypher-shell -u neo4j -p password

.PHONY: clickhouse-cli
clickhouse-cli: ## Open a clickhouse-client session
	docker compose exec clickhouse clickhouse-client

# --- TUI ------------------------------------------------------------------

.PHONY: tui
tui: ## Run an interactive TUI session against the gateway
	docker compose run --rm tui-service
