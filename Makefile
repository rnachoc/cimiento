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
test: test-core test-py test-console ## Run every test suite

.PHONY: test-core
test-core: data-if-missing
	@command -v java >/dev/null || { echo "JDK 17+ is required for the core (see docs/decisions/0001)"; exit 2; }
	$(CORE_MVN) -q -B verify

# The core checks its engine against the generated ground truth.
.PHONY: data-if-missing
data-if-missing:
	@if [ ! -f "$(DATA_DIR)/dev/manifest.json" ] || [ ! -f "$(DATA_DIR)/eval/manifest.json" ]; then \
	  $(MAKE) --no-print-directory data; fi

.PHONY: test-console
test-console:
	cd web/console && npm ci --no-audit --no-fund --loglevel=error && npx ng test --watch=false

# Real browser against core + console in docker compose (needs Docker and Playwright's Chromium).
.PHONY: e2e
e2e: ## End-to-end: a real browser approves a proposal through the console
	cd web/console && npm ci --no-audit --no-fund --loglevel=error && npx playwright install chromium
	./scripts/e2e.sh

# Tender intake -> agents -> proposals -> decision through a real Kafka, with dead letters and replay.
.PHONY: flow
flow: data-if-missing ## End-to-end event flow through Kafka (outbox, bridge, retries, dead letters)
	./scripts/flow.sh

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
demo: ## Keyless end-to-end demo: data, core, console, agents; open the printed console URL
	./scripts/demo.sh

.PHONY: demo-down
demo-down: ## Stop the demo containers and remove their data
	@[ -f .env ] || cp .env.example .env; set -a; if [ -f .demo-keys ]; then . ./.demo-keys; fi; set +a; \
	export CIMIENTO_AGENT_KEY=$${CIMIENTO_AGENT_KEY:-unused-a} CIMIENTO_ANALYST_KEY=$${CIMIENTO_ANALYST_KEY:-unused-b} \
	  CIMIENTO_REVIEWER_KEY=$${CIMIENTO_REVIEWER_KEY:-unused-c}; \
	docker compose -p cimiento-demo -f deploy/docker-compose.yml --env-file .env --profile app down -v --remove-orphans

.PHONY: eval
eval: eval-retrieval eval-bid eval-plan eval-site ## Run every eval implemented so far

# Keyless by default (rule-based reader, labelled as such). With ANTHROPIC_API_KEY it uses Claude.
.PHONY: eval-bid
eval-bid: data-if-missing ## Bid analyst: fields, citations, planted instructions; writes evals/reports/bid.md
	cd evals && uv run cimiento-evals bid --data ../data/generated --out reports --recordings recordings

.PHONY: eval-site
eval-site: data-if-missing ## Site progress: planted delays, alarms, reports; writes evals/reports/site.md
	cd evals && uv run cimiento-evals site --data ../data/generated --out reports --recordings recordings

# Spans of a bid eval run -> tokens and cost per model (priced models only) + no-content check.
# OpenTelemetry collector, Prometheus, Jaeger and Grafana; telemetry from a few tenders; checks.
.PHONY: observability
observability: data-if-missing ## Observability stack check (KEEP=1 leaves Grafana running on :23000)
	./scripts/observability.sh

.PHONY: cost-report
cost-report: data-if-missing ## Tokens and cost per model from spans; fails if a span carries document text
	rm -f evals/reports/spans.jsonl
	cd evals && CIMIENTO_TRACE_FILE=$(CURDIR)/evals/reports/spans.jsonl uv run cimiento-evals bid --data ../data/generated --out reports --recordings recordings >/dev/null
	cd evals && uv run cimiento-evals cost --spans reports/spans.jsonl --prices config/prices.json --data ../data/generated --out reports

.PHONY: eval-plan
eval-plan: data-if-missing ## Planner: valid schedules, duration error, critical path; writes evals/reports/plan.md
	cd evals && uv run cimiento-evals plan --data ../data/generated --out reports --recordings recordings

# Local models are cached in ~/.cache/fastembed (first run downloads about 0.8 GB).
.PHONY: eval-retrieval
eval-retrieval: data-if-missing ## Retrieval hit@1/hit@5 per method; writes evals/reports/retrieval.md
	cd evals && FASTEMBED_CACHE_PATH=$${FASTEMBED_CACHE_PATH:-$$HOME/.cache/fastembed} \
	  uv run cimiento-evals retrieval --data ../data/generated --out reports --config config/search.json

# Runs the bid, planning and site evals (keyless unless ANTHROPIC_API_KEY is set) and gates them
# against evals/baseline.json: harms must be 0, scores may only drop within the tolerance.
# Real models through the Batch API (half price). Reads ANTHROPIC_API_KEY from .env (git-ignored).
# Without CONFIRM=1 it only counts the requests; with CONFIRM=1 it spends money.
MODELS ?= claude-haiku-5-5 claude-sonnet-5-5 claude-opus-5-5
.PHONY: eval-real
eval-real: data-if-missing ## Real-model run per model (MODELS=..., CONFIRM=1 to send the batches)
	@set -a; . ./.env; set +a; 	[ -n "$$ANTHROPIC_API_KEY" ] || [ -z "$(CONFIRM)" ] || { echo "ANTHROPIC_API_KEY is not set in .env"; exit 2; }; 	for m in $(MODELS); do 	  (cd evals && uv run cimiento-evals real --model $$m --data ../data/generated --recordings recordings 	     --out reports --prices config/prices.json $(if $(CONFIRM),--confirm,)) || exit 1; 	done

.PHONY: scorecard
scorecard: eval-bid eval-plan eval-site ## Gate: harms 0 and scores within tolerance of evals/baseline.json
	cd evals && uv run cimiento-evals scorecard --reports reports --baseline baseline.json
