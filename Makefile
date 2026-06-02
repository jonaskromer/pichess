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
	@grep -hE '^[a-zA-Z_-]+:[^:]*?##' $(MAKEFILE_LIST) | sort | \
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
                --profile tui --profile obs

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

define _stack_up
	@mkdir -p $(dir $(STACK_STATE_FILE)) || true
	@echo "PICHESS_BACKEND=$(1) PICHESS_EXTRAS=$(EXTRA)" > $(STACK_STATE_FILE)
	docker compose $(ALL_PROFILES) down 2>/dev/null || true
	PICHESS_BACKEND=$(1) PICHESS_EXTRAS=$(EXTRA) \
	  PICHESS_KAFKA="$(KAFKA_FOR_EXTRA)" \
	  docker compose --profile $(1) $(EXTRA_PROFILES) up -d
endef

.PHONY: stack-postgres
stack-postgres: ## Start the stack with PICHESS_BACKEND=postgres
	$(call _stack_up,postgres)

.PHONY: stack-mongo
stack-mongo: ## Start the stack with PICHESS_BACKEND=mongo
	$(call _stack_up,mongo)

.PHONY: stack-cassandra
stack-cassandra: ## Start the stack with PICHESS_BACKEND=cassandra
	$(call _stack_up,cassandra)

.PHONY: stack-redis
stack-redis: ## Start the stack with PICHESS_BACKEND=redis
	$(call _stack_up,redis)

.PHONY: stack-inmemory
stack-inmemory: ## Start the stack with no DB (PICHESS_BACKEND=inmemory)
	@mkdir -p $(dir $(STACK_STATE_FILE)) || true
	@echo "PICHESS_BACKEND=inmemory PICHESS_EXTRAS=$(EXTRA)" > $(STACK_STATE_FILE)
	docker compose $(ALL_PROFILES) down 2>/dev/null || true
	PICHESS_BACKEND=inmemory PICHESS_EXTRAS=$(EXTRA) \
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

# --- Performance / profiling -----------------------------------------------
#
# `bench` runs the JMH microbenchmark suite and writes a JSON result that
# the perf harness folds into its summary. `perf` switches stacks across
# the requested backends and runs the chosen Gatling simulation against
# each. `profile-async-cpu` attaches async-profiler to a running service
# container for a fixed duration.

PERF_REPORTS_DIR := perf-reports

.PHONY: bench
bench: ## Run the JMH microbenchmark suite + write JSON to perf-reports
	@mkdir -p $(PERF_REPORTS_DIR)
	@ts=$$(date -u +%Y%m%dT%H%M%SZ); \
	out=$(PERF_REPORTS_DIR)/bench-$$ts.json; \
	sbt "bench/Jmh/run -i 3 -wi 3 -f1 -rf json -rff $$out"; \
	echo "JMH results → $$out"

.PHONY: perf
perf: ## Cross-backend Gatling harness. Vars: BACKENDS, MODE, OBS, PEAK_USERS, …
	scripts/perf-run.sh

.PHONY: perf-summary
perf-summary: ## Rebuild comparison.md for the most recent perf run
	@latest=$$(ls -dt $(PERF_REPORTS_DIR)/*/ 2>/dev/null | head -1); \
	if [ -z "$$latest" ]; then \
	  echo "no runs found under $(PERF_REPORTS_DIR)/"; exit 1; \
	fi; \
	scripts/perf-summary.sh "$$latest"

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
