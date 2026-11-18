SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE := docker compose -f deploy/docker-compose.yml --env-file .env
MAVEN_IMAGE := maven:3.9.11-eclipse-temurin-17
export UV_LINK_MODE ?= copy

# Use the local JDK when present; otherwise build the core inside a JDK 17 container.
ifeq ($(shell command -v java 2>/dev/null),)
CORE_MVN := docker run --rm -v "$(CURDIR)/services/core:/w" -v cimiento-m2:/root/.m2 -w /w $(MAVEN_IMAGE) ./mvnw
else
CORE_MVN := cd services/core && ./mvnw
endif

PY_SERVICES := services/agents services/mcp

.PHONY: help
help: ## List targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-12s %s\n", $$1, $$2}'

.env:
	cp .env.example .env

.PHONY: up
up: .env ## Start Postgres (pgvector) and Kafka and wait until healthy
	$(COMPOSE) up -d --wait

.PHONY: down
down: .env ## Stop local services (keeps volumes)
	$(COMPOSE) down

.PHONY: test
test: test-core test-py ## Run every test suite

.PHONY: test-core
test-core:
	$(CORE_MVN) -q -B verify

.PHONY: test-py
test-py:
	@for s in $(PY_SERVICES); do echo "== $$s"; (cd $$s && uv run pytest -q) || exit 1; done

.PHONY: lint
lint: ## Lint and format checks
	@for s in $(PY_SERVICES); do echo "== $$s"; (cd $$s && uv run ruff check . && uv run ruff format --check .) || exit 1; done

.PHONY: data
data: ## Generate synthetic data (phase 1)
	@echo "Not implemented yet: arrives in phase 1." && exit 1

.PHONY: demo
demo: ## Keyless end-to-end demo (phase 12)
	@echo "Not implemented yet: arrives in phase 12." && exit 1

.PHONY: eval
eval: ## Run evals (phases 3 to 6)
	@echo "Not implemented yet: arrives in phases 3 to 6." && exit 1

.PHONY: scorecard
scorecard: ## Compare evals against evals/baseline.json (phase 11)
	@echo "Not implemented yet: arrives in phase 11." && exit 1
