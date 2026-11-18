# ADR 0011: Observability and cost

Status: accepted
Date: 2026-11-18

## Context
Every tender should be traceable through its agents, model calls and core requests, with token counts and costs, without telemetry ever carrying document text.

## Decisions
- **OpenTelemetry SDK** in the agents (`opentelemetry-sdk`, OTLP over HTTP). One trace per tender (`cimiento.tender`), one span per agent (`agent.*`, through a `traced` wrapper), one span per model call following the GenAI semantic conventions (`chat <model>` with `gen_ai.operation.name`, `gen_ai.provider.name`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`) and one per core request (`POST /api/proposals`, with `http.request.method`, `url.template`, `http.response.status_code`).
- **Metrics**: `gen_ai.client.token.usage` (histogram per call, by token type), `gen_ai.client.operation.duration`, `cimiento.proposals.submitted`.
- **No content by construction**: every span attribute passes the same check as telemetry events (identifiers, counts, timings, flags; no spaces, at most 64 characters). A test runs every eval tender, including those with planted instructions, and checks every attribute; `make cost-report` checks again against all document text and fails on any match.
- **Backends** (compose profile `observability`, pinned images): OpenTelemetry Collector to Jaeger (traces) and Prometheus (metrics), Grafana with provisioned data sources and a dashboard (proposals, model calls, tokens by model and type, p95 latency). `make observability` checks that every dashboard query returns data, that Jaeger has one trace per tender with agent, model and core spans, and that the dashboard is provisioned.
- **Cost**: `make cost-report` reads the spans of an eval run and converts tokens to money only for models with a price in `evals/config/prices.json` (list prices in USD per million tokens). Euros appear only when the user sets an exchange rate; no rate is assumed. The rule-based reader and recorded answers are listed with no price.

## Consequences
- Keyless runs show zero tokens; real costs appear in phase 11 with Claude.
