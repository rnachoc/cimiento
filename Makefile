SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE := docker compose -f deploy/docker-compose.yml --env-file .env
MAVEN_IMAGE := maven:3.9.11-eclipse-temurin-17
export UV_LINK_MODE ?= copy

# CORE_JDK=auto uses the local JDK when present, otherwise a JDK 17 container.
# CORE_JDK=docker forces the container. The container runs as the host user so
# build output is not owned by root; its Maven cache lives in services/core/.m2repo.
CORE_JDK ?= auto
ifeq ($(CORE_JDK),auto)
ifeq ($(shell command -v java 2>/dev/null),)
CORE_JDK := docker
else
CORE_JDK := local
endif
endif
ifeq ($(CORE_JDK),docker)
CORE_MVN := docker run --rm --user "$$(id -u):$$(id -g)" -e HOME=/tmp -e MAVEN_USER_HOME=/w/.m2repo \
	-v "$(CURDIR)/services/core:/w" -w /w $(MAVEN_IMAGE) ./mvnw -Dmaven.repo.local=/w/.m2repo/repository
else
CORE_MVN := cd services/core && ./mvnw
endif

PY_SERVICES := data/generator services/agents services/mcp
DATA_DIR := data/generated
DEV_SEED := 42
EVAL_SEED := 1337

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
data: ## Generate both splits (dev 42, eval 1337); or SPLIT=dev|eval and/or SEED=n (SEED alone means dev)
	@set -e; \
	if [ -n "$(SPLIT)" ]; then splits="$(SPLIT)"; elif [ -n "$(SEED)" ]; then splits=dev; else splits="dev eval"; fi; \
	for s in $$splits; do \
	  case $$s in dev) seed=$(DEV_SEED);; eval) seed=$(EVAL_SEED);; *) echo "SPLIT must be dev or eval"; exit 2;; esac; \
	  if [ -n "$(SEED)" ]; then seed=$(SEED); fi; \
	  rm -rf "$(DATA_DIR)/$$s"; \
	  (cd data/generator && uv run cimiento-data --seed $$seed --split $$s --out "../generated/$$s"); \
	done

.PHONY: demo
demo: ## Keyless end-to-end demo (phase 12)
	@echo "Not implemented yet: arrives in phase 12." && exit 1

.PHONY: eval
eval: ## Run evals (phases 3 to 6)
	@echo "Not implemented yet: arrives in phases 3 to 6." && exit 1

.PHONY: scorecard
scorecard: ## Compare evals against evals/baseline.json (phase 11)
	@echo "Not implemented yet: arrives in phase 11." && exit 1
