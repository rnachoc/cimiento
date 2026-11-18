#!/usr/bin/env bash
# Keyless end-to-end demo: synthetic data, core + review console in Docker, the agents (rule-based
# reader, or Claude only if ANTHROPIC_API_KEY is exported in the shell) analyse a few tenders, and the proposals
# wait in the console for a person. Needs Docker, make and uv. No API key, no JDK, no Node.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

[ -f .env ] || cp .env.example .env
STATE="$ROOT/.demo-keys"   # git-ignored; lets `make demo-down` and reruns reuse the same keys
if [ ! -f "$STATE" ]; then
  rand() { printf '%s-%s' "$1" "$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"; }
  printf 'CIMIENTO_AGENT_KEY=%s\nCIMIENTO_ANALYST_KEY=%s\nCIMIENTO_REVIEWER_KEY=%s\n' \
    "$(rand agent)" "$(rand analyst)" "$(rand reviewer)" > "$STATE"
  chmod 600 "$STATE"
fi
if ! grep -q '^CIMIENTO_PEOPLE=' "$STATE"; then   # people arrived in phase 15; older state files get them too
  . "$ROOT/scripts/people.sh"
  people_env >> "$STATE"
fi
set -a; . "$STATE"; set +a
export POSTGRES_PORT="${DEMO_POSTGRES_PORT:-35432}" CORE_PORT="${DEMO_CORE_PORT:-38080}" CONSOLE_PORT="${DEMO_CONSOLE_PORT:-38088}"
CORE_URL="http://localhost:${CORE_PORT}"
COMPOSE=(docker compose -p cimiento-demo -f "$ROOT/deploy/docker-compose.yml" --env-file "$ROOT/.env" --profile app)

echo "==> 1/4 synthetic data"
[ -f data/generated/eval/manifest.json ] || make --no-print-directory data

echo "==> 2/4 core and review console (first run builds the images, a few minutes)"
"${COMPOSE[@]}" up -d --wait postgres
"${COMPOSE[@]}" up -d --build core console
for i in $(seq 1 120); do
  curl -sf "${CORE_URL}/actuator/health" >/dev/null && curl -sf "http://localhost:${CONSOLE_PORT}/" >/dev/null && break
  [ "$i" = 120 ] && { echo "services did not start"; "${COMPOSE[@]}" logs core | tail -30; exit 1; }
  sleep 2
done

echo "==> 3/4 agents analyse six tenders (eval split)"
cd "$ROOT/services/agents"
CORE_URL="$CORE_URL" DATA_ROOT="$ROOT/data/generated" uv run --quiet python "$ROOT/scripts/demo_run.py"

echo "==> 4/4 committed scorecard (all 30 eval tenders; rerun it with: make scorecard)"
sed -n '1,12p' "$ROOT/evals/reports/scorecard.md"
cat <<EOF

Review console:  http://localhost:${CONSOLE_PORT}   (approve, correct or reject the proposals)
  Sign in as:    gerencia / ${GERENCIA_TOKEN}   (management: decides everything)
                 estudios / ${ESTUDIOS_TOKEN}   (estimating: bids, takeoffs, budgets)
                 obra     / ${OBRA_TOKEN}   (site manager: schedules, progress, reports)
Core API:        ${CORE_URL}/api  (role keys in .demo-keys)
Stop everything: make demo-down
EOF
