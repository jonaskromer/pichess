# pichess developer entrypoints. Replaces the older scripts/dev-up.sh —
# every target is documented inline; run `make` (or `make help`) to list them.
#
# Service names below match the docker-compose service names (kebab-case).
# The corresponding sbt subproject names are camelCase, hence the per-service
# build- targets that bridge between the two.

.DEFAULT_GOAL := help

# --- Stack-wide targets ---------------------------------------------------

.PHONY: help
help: ## Show this target list
	@grep -hE '^[a-zA-Z_-]+:[^:]*?##' $(MAKEFILE_LIST) | sort | \
	  awk -F ':.*?##' '{printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

.PHONY: build
build: ## Build all service images via sbt dockerBuildAll
	sbt dockerBuildAll

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

# --- Per-service rebuild + restart ----------------------------------------
#
# Each `build-X` republishes the image to the local Docker daemon; each
# `dev-X` does that *and* restarts only that container with --no-deps so
# the DBs and other services keep running.

.PHONY: build-gateway
build-gateway: ## Rebuild gateway image
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
