#!/usr/bin/env bash
# End-to-end test: core + console (nginx) + Postgres with docker compose, then Playwright in a
# real Chromium. Role keys are random for each run and never written to disk.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

[ -f .env ] || cp .env.example .env
rand() { printf '%s-%s' "$1" "$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"; }
export CIMIENTO_AGENT_KEY="$(rand agent)" CIMIENTO_ANALYST_KEY="$(rand analyst)" CIMIENTO_REVIEWER_KEY="$(rand reviewer)"
export CORE_PORT="${CORE_PORT:-18080}" CONSOLE_PORT="${CONSOLE_PORT:-18088}" POSTGRES_PORT="${E2E_POSTGRES_PORT:-15432}"
export CORE_URL="http://localhost:${CORE_PORT}" CONSOLE_URL="http://localhost:${CONSOLE_PORT}"
COMPOSE=(docker compose -p cimiento-e2e -f "$ROOT/deploy/docker-compose.yml" --env-file "$ROOT/.env" --profile app)

# absolute paths: the script changes directory before this runs
cleanup() { "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || echo "warning: could not stop the e2e stack"; }
trap cleanup EXIT

"${COMPOSE[@]}" up -d --build --wait postgres
"${COMPOSE[@]}" up -d --build core console

for i in $(seq 1 120); do
  if curl -sf "${CORE_URL}/actuator/health" >/dev/null && curl -sf "${CONSOLE_URL}/" >/dev/null; then break; fi
  [ "$i" = 120 ] && { echo "services did not start"; "${COMPOSE[@]}" logs core console | tail -50; exit 1; }
  sleep 2
done

cd web/console
npx playwright test
