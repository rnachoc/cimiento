#!/usr/bin/env bash
# Observability stack (OpenTelemetry collector, Prometheus, Jaeger, Grafana) in docker compose,
# then telemetry from a few tenders through OTLP and checks on each backend.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
[ -f .env ] || cp .env.example .env
export OTLP_HTTP_PORT="${OBS_OTLP_PORT:-24318}" PROMETHEUS_PORT="${OBS_PROM_PORT:-29090}" \
       GRAFANA_PORT="${OBS_GRAFANA_PORT:-23000}" JAEGER_UI_PORT="${OBS_JAEGER_PORT:-26686}"
# The app profile is not started; dummy keys only satisfy compose variable checks.
export CIMIENTO_AGENT_KEY=unused-a CIMIENTO_ANALYST_KEY=unused-b CIMIENTO_REVIEWER_KEY=unused-c
COMPOSE=(docker compose -p cimiento-obs -f "$ROOT/deploy/docker-compose.yml" --env-file "$ROOT/.env" --profile observability)

cleanup() { "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || echo "warning: could not stop the observability stack"; }
[ "${KEEP:-0}" = 1 ] || trap cleanup EXIT

"${COMPOSE[@]}" up -d otel-collector jaeger prometheus grafana
for i in $(seq 1 60); do
  curl -sf "http://localhost:${GRAFANA_PORT}/api/health" >/dev/null && curl -sf "http://localhost:${PROMETHEUS_PORT}/-/ready" >/dev/null && break
  sleep 2
done

export OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:${OTLP_HTTP_PORT}"
export PROM_URL="http://localhost:${PROMETHEUS_PORT}" JAEGER_URL="http://localhost:${JAEGER_UI_PORT}" \
       GRAFANA_URL="http://localhost:${GRAFANA_PORT}" DATA_ROOT="$ROOT/data/generated" \
       DASHBOARD_JSON="$ROOT/deploy/observability/grafana/dashboards/cimiento.json"
[ -f "$ROOT/data/generated/eval/manifest.json" ] || make -C "$ROOT" data
cd "$ROOT/services/agents" && uv run python "$ROOT/scripts/observability_check.py"
