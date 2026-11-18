#!/usr/bin/env bash
# Full flow through a real Kafka: Postgres, Kafka and the core (outbox on) in docker compose,
# then the Python bridge and consumers (scripts/flow_check.py). Keys are random per run.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

[ -f .env ] || cp .env.example .env
rand() { printf '%s-%s' "$1" "$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"; }
export CIMIENTO_AGENT_KEY="$(rand agent)" CIMIENTO_ANALYST_KEY="$(rand analyst)" CIMIENTO_REVIEWER_KEY="$(rand reviewer)"
. "$ROOT/scripts/people.sh"
eval "$(people_env)"
export GERENCIA_TOKEN ESTUDIOS_TOKEN OBRA_TOKEN CIMIENTO_PEOPLE
export POSTGRES_PORT="${FLOW_POSTGRES_PORT:-25432}" KAFKA_PORT="${FLOW_KAFKA_PORT:-29092}" CORE_PORT="${FLOW_CORE_PORT:-28080}"
export CIMIENTO_OUTBOX_ENABLED=true
export CORE_URL="http://localhost:${CORE_PORT}" KAFKA_BOOTSTRAP="localhost:${KAFKA_PORT}" DATA_ROOT="$ROOT/data/generated"
COMPOSE=(docker compose -p cimiento-flow -f "$ROOT/deploy/docker-compose.yml" --env-file "$ROOT/.env" --profile app)

cleanup() { "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || echo "warning: could not stop the flow stack"; }
trap cleanup EXIT

"${COMPOSE[@]}" up -d --wait postgres kafka
"${COMPOSE[@]}" up -d --build core
for i in $(seq 1 120); do
  curl -sf "${CORE_URL}/actuator/health" >/dev/null && break
  [ "$i" = 120 ] && { echo "core did not start"; "${COMPOSE[@]}" logs core | tail -50; exit 1; }
  sleep 2
done

[ -f "$ROOT/data/generated/dev/manifest.json" ] || make -C "$ROOT" data
cd "$ROOT/services/agents" && uv run python "$ROOT/scripts/flow_check.py"
