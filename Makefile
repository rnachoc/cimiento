SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE := docker compose -f deploy/docker-compose.yml --env-file .env
export UV_LINK_MODE ?= copy

# The core needs a local JDK 17+ and Docker (integration tests use Testcontainers).
CORE_MVN := cd services/core && ./mvnw

PY_SERVICES := data/generator services/agents services/mcp evals
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
test-core: data-if-missing
	@command -v java >/dev/null || { echo "JDK 17+ is required for the core (see docs/decisions/0001)"; exit 2; }
	$(CORE_MVN) -q -B verify

# The core checks its engine against the generated ground truth.
.PHONY: data-if-missing
data-if-missing:
	@if [ ! -f "$(DATA_DIR)/dev/manifest.json" ] || [ ! -f "$(DATA_DIR)/eval/manifest.json" ]; then \
	  $(MAKE) --no-print-directory data; fi

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
eval: eval-retrieval eval-bid eval-plan eval-site ## Run every eval implemented so far

# Keyless by default (rule-based reader, labelled as such). With ANTHROPIC_API_KEY it uses Claude.
.PHONY: eval-bid
eval-bid: data-if-missing ## Bid analyst: fields, citations, planted instructions; writes evals/reports/bid.md
	cd evals && uv run cimiento-evals bid --data ../data/generated --out reports --recordings recordings

.PHONY: eval-site
eval-site: data-if-missing ## Site progress: planted delays, alarms, reports; writes evals/reports/site.md
	cd evals && uv run cimiento-evals site --data ../data/generated --out reports --recordings recordings

.PHONY: eval-plan
eval-plan: data-if-missing ## Planner: valid schedules, duration error, critical path; writes evals/reports/plan.md
	cd evals && uv run cimiento-evals plan --data ../data/generated --out reports --recordings recordings

# Local models are cached in ~/.cache/fastembed (first run downloads about 0.8 GB).
.PHONY: eval-retrieval
eval-retrieval: data-if-missing ## Retrieval hit@1/hit@5 per method; writes evals/reports/retrieval.md
	cd evals && FASTEMBED_CACHE_PATH=$${FASTEMBED_CACHE_PATH:-$$HOME/.cache/fastembed} \
	  uv run cimiento-evals retrieval --data ../data/generated --out reports --config config/search.json

.PHONY: scorecard
scorecard: ## Compare evals against evals/baseline.json (phase 11)
	@echo "Not implemented yet: arrives in phase 11." && exit 1
