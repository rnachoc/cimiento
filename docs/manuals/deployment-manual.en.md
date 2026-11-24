# Cimiento: deployment manual for a company

Application version: `main` branch, November 2026. This manual explains how to install Cimiento on a company server, configure it, secure it, back it up and update it. It is written for the IT person who sets it up.

## 0. Before you start: where it stands

Cimiento is a portfolio project tested on **synthetic data**. `SECURITY.md` says so plainly: it is not meant for production as it is. Running it in a real company makes sense as a **controlled pilot**, with real people reviewing every proposal, which is how it is designed to work.

Ready today:

- The core with its rules, the hash-chained audit log and review by people with roles and four eyes.
- The web console: review inbox, bid pipeline, tender files, site progress, certifications with PDF, work calendar and management view.
- Docker containers, automatic database migrations, a read-only MCP server and observability.

To solve before using it with real tenders:

| Topic | Today | What to do |
|---|---|---|
| Tender intake | No upload screen. Analysis is started by a script or through the Kafka bridge `cimiento.intake`. | Follow section 10 or build a small intake service. |
| Reading real documents | The keyless reader is written for the format of the synthetic documents. | Real documents need a model (`ANTHROPIC_API_KEY`). Quality with a real model is **not measured**: measure it on your own tenders before relying on it. |
| Identity | Personal tokens. No corporate single sign-on (OIDC or SAML) and no lockout after failed attempts. | Accept it for the pilot or integrate your identity provider. |
| Removing people | Taking someone out of `CIMIENTO_PEOPLE` does not disable them. | Replace their `tokenHash` (section 5). |
| HTTPS | The console proxy does not forward `X-Forwarded-Proto`, so the session cookie lacks the `Secure` flag. | Add one line before building the console (section 8). |
| Rates | The fictional contractor's rates ship by default. | Load your own (section 9). |
| Backups | Not included. | Schedule them (section 12). |

## 1. Components

| Component | Technology | Internal port | Required? |
|---|---|---|---|
| Core (`core`) | Java 17, Spring Boot 3 | 8080 | Yes |
| Console (`console`) | Angular served by nginx | 8080 (published on 8088) | Yes |
| Database (`postgres`) | PostgreSQL 16 with pgvector | 5432 | Yes |
| Messaging (`kafka`) | Apache Kafka 3.9 | 9092 | No (events) |
| Agents | Python 3.12 with uv | - | Yes, to analyse tenders |
| Document search | Python, FastAPI | 8001 | No |
| MCP server | Python, read-only | - | No |
| Observability | OpenTelemetry, Jaeger, Prometheus, Grafana | several | No |

The browser only talks to the console. The console adds the reviewer key on the server side and forwards `/api/` to the core; the browser never sees a key. Each person signs in with their token and is carried in an `HttpOnly` session cookie.

## 2. Requirements

- A Linux x86_64 server, for example Ubuntu Server 24.04 LTS.
- Docker Engine with Docker Compose 2.24.4 or later (the file in section 6 uses `!override`).
- As a guide (not measured under real load): 4 vCPU, 8 GB RAM and 50 GB disk.
- An internal or public domain name, for example `cimiento.yourcompany.com`, and its TLS certificate.
- For the agents: Python 3.12 and [uv](https://docs.astral.sh/uv/).
- If a Claude model is used: outbound HTTPS to `api.anthropic.com` and a company API key.
- `git` and `openssl`.

## 3. Get the code

```bash
sudo mkdir -p /opt/cimiento && sudo chown "$USER" /opt/cimiento
git clone https://github.com/rnachoc/cimiento.git /opt/cimiento
cd /opt/cimiento
git checkout <tested-commit-or-tag>
```

Always pin a specific version; do not deploy the latest `main` without testing it.

## 4. Configuration (`.env`)

```bash
cp .env.example .env
chmod 600 .env
```

Generate a different random value for each secret:

```bash
openssl rand -hex 32
```

| Variable | Purpose | Value |
|---|---|---|
| `POSTGRES_DB`, `POSTGRES_USER` | Database | `cimiento` (or your choice) |
| `POSTGRES_PASSWORD` | Database password | Random |
| `CIMIENTO_AGENT_KEY` | Agents' key: can only submit proposals | Random |
| `CIMIENTO_ANALYST_KEY` | Read-only key (MCP, reports) | Random |
| `CIMIENTO_REVIEWER_KEY` | Key the console uses to decide | Random |
| `CIMIENTO_PEOPLE` | People who decide (section 5) | JSON on one line |
| `CIMIENTO_OUTBOX_ENABLED` | Publish the audit log to Kafka | `false` unless you use Kafka |
| `CORE_PORT`, `CONSOLE_PORT` | Ports published on the server | `8080` and `8088` |
| `ANTHROPIC_API_KEY` | Agents only, if they use Claude | Company key or empty |

The three role keys must be different. The core **refuses to start** while they still hold the sample values of `.env.example`.

Business parameters (set in the file of section 6):

| Variable | Default | Meaning |
|---|---|---|
| `CIMIENTO_RETENTION_PCT` | 5 | Retention of each certification, in % |
| `CIMIENTO_VAT_PCT` | 21 | VAT of each certification, in % |
| `CIMIENTO_REVIEW_AMOUNTTHRESHOLDEUR` | 1000000 | Budgets from this amount (VAT excluded) are decided by management only |

## 5. People and tokens

Each person has a username, a display name, a role and a personal token. The core only stores the **SHA-256 hash** of the token. Roles: `ESTIMATING`, `SITE_MANAGER` and `MANAGEMENT`. The user manual explains what each one can do.

For each person:

```bash
TOKEN=$(openssl rand -hex 24)
echo "Token to hand to the person: $TOKEN"
printf '%s' "$TOKEN" | sha256sum | cut -d' ' -f1   # this hash goes into .env
```

Then, in `.env`, on a single line:

```bash
CIMIENTO_PEOPLE='[{"username":"mgarcia","displayName":"María García","role":"MANAGEMENT","tokenHash":"<hash>"},{"username":"lperez","displayName":"Luis Pérez","role":"ESTIMATING","tokenHash":"<hash>"},{"username":"aruiz","displayName":"Ana Ruiz","role":"SITE_MANAGER","tokenHash":"<hash>"}]'
```

- Hand each token over a secure channel (in person or through a password manager). Do not keep it on the server.
- Changes apply when the core restarts (section 7).
- **New token** (lost or rotated): generate another, replace their `tokenHash` and restart. The old one stops working.
- **Removing someone**: taking them out of the list does **not** disable them, because the core keeps their row. Replace their `tokenHash` with the hash of a random value nobody knows and restart.
- Sessions last 8 hours.

## 6. Production file

`deploy/docker-compose.yml` publishes every port on every interface, which is fine on a laptop but not on a server. Create `deploy/docker-compose.prod.yml` (do not commit it) to publish on localhost only, restart automatically and set the parameters:

```yaml
services:
  postgres:
    restart: unless-stopped
    ports: !override
      - "127.0.0.1:5432:5432"
  kafka:
    restart: unless-stopped
    ports: !override
      - "127.0.0.1:9092:9092"
  core:
    restart: unless-stopped
    ports: !override
      - "127.0.0.1:8080:8080"
    environment:
      CIMIENTO_RETENTION_PCT: "5"
      CIMIENTO_VAT_PCT: "21"
      CIMIENTO_REVIEW_AMOUNTTHRESHOLDEUR: "1000000"
  console:
    restart: unless-stopped
    ports: !override
      - "127.0.0.1:8088:8080"
```

From outside, only the HTTPS proxy of section 8 will be reachable.

## 7. Start

To avoid repeating the command, define an alias:

```bash
alias cim='docker compose -p cimiento -f /opt/cimiento/deploy/docker-compose.yml -f /opt/cimiento/deploy/docker-compose.prod.yml --env-file /opt/cimiento/.env --profile app'
```

First time (builds the images; takes several minutes):

```bash
cim up -d --build
```

Checks:

```bash
cim ps                                         # everything "running" or "healthy"
curl -s http://127.0.0.1:8080/actuator/health  # {"status":"UP"}
curl -sI http://127.0.0.1:8088/ | head -1      # HTTP/1.1 200 OK
```

Database migrations run by themselves when the core starts.

Other commands:

```bash
cim up -d core              # restart the core after changing .env
cim logs -f core            # follow the core log
cim stop                    # stop without deleting data
```

Do not use `cim down -v`: `-v` deletes the volumes, that is, the database.

## 8. HTTPS and network access

1. **Make the session cookie secure.** Before building the console, edit `web/console/nginx/default.conf.template` and, inside `location /api/ { ... }`, add:

   ```nginx
   proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
   ```

   Rebuild: `cim up -d --build console`. The core marks the cookie `Secure` when the request arrives over HTTPS.

2. **HTTPS proxy in front of the console.** For example with Caddy, which obtains and renews the certificate:

   ```
   cimiento.yourcompany.com {
       reverse_proxy 127.0.0.1:8088
   }
   ```

   With another proxy (nginx, Traefik, the company load balancer), send `X-Forwarded-Proto: https` to the console.

3. **Firewall.** Open only 443 (and 80 if you redirect to 443). Example with ufw:

   ```bash
   sudo ufw default deny incoming
   sudo ufw allow OpenSSH
   sudo ufw allow 443/tcp
   sudo ufw enable
   ```

4. **Check.** Open `https://cimiento.yourcompany.com`, sign in and check in the browser tools that the `cimiento_session` cookie has `HttpOnly`, `Secure` and `SameSite=Strict`.

## 9. Company rates

Rates (price per unit and output per day) feed the budget and the schedule. The shipped ones belong to the fictional contractor.

Each item needs: code, description, activity, unit, unit price (VAT excluded) and output per day. Activities must be the ones the planner knows, from `A01` (earthworks) to `A12` (external works). Adding new activities means changing the planner (`services/agents/src/cimiento_agents/planner.py`) and its dependency table.

Rates live in two places, and they must match:

1. **In the core** (table `rate`). To load or change an item:

   ```bash
   cim exec postgres psql -U cimiento -d cimiento -c "
     INSERT INTO rate (code, description, activity, unit, unit_price, rate_per_day)
     VALUES ('MT01', 'Excavación en zanjas y pozos', 'A01', 'm3', 19.20, 55)
     ON CONFLICT (code) DO UPDATE SET description = EXCLUDED.description, activity = EXCLUDED.activity,
       unit = EXCLUDED.unit, unit_price = EXCLUDED.unit_price, rate_per_day = EXCLUDED.rate_per_day;"
   ```

2. **For the agents**, in a `tarifas.json` with the same format as `data/generated/eval/company/tarifas.json` (fields `codigo`, `descripcion`, `actividad`, `unidad`, `precio_unitario_eur`, `rendimiento_por_dia`).

## 10. Analysing tenders

The agents run on the server (or on another machine that can reach the core) with Python and uv:

```bash
cd /opt/cimiento/services/agents
uv sync
set -a; . /opt/cimiento/.env; set +a           # role keys and ANTHROPIC_API_KEY
export CORE_URL=http://127.0.0.1:8080
export CIMIENTO_MODEL=claude-opus-5-5          # optional: the model your company uses
```

Without `ANTHROPIC_API_KEY`, the agents use the rule-based reader, which costs nothing.

Each tender is a folder with up to three PDFs with fixed names: `pliego.pdf` (specification), `anexo.pdf` (annex) and `mediciones.pdf` (bill of quantities). For site progress, add `obra/avance_semanal.csv` with the same format as the sample data.

Today analysis is started by a script. `scripts/demo_run.py` is the example: it creates the project, loads the rates and calls `analyse_tender(folder, model, core, planned_start, rates)` for each tender. Copy it, change the list of folders, the path of `tarifas.json` and the planned start of each work (a Monday), and run it with `uv run python your_script.py`. The proposals appear in the console inbox.

For continuous use, the intended path is the event bridge: publish a message on the Kafka topic `cimiento.intake` for each new tender and run a process that consumes it with `services/agents/src/cimiento_agents/events.py` (retries and dead-letter queue included; tested by `make flow`). That service process is not packaged.

With a model, the text of the documents is sent to the Anthropic API. Check with the company whether that is acceptable for its tenders.

## 11. Optional parts

**MCP server (questions from an assistant).** Read-only, with the analyst key. Sample configuration in `README.md` (section "Use it from an assistant").

**Kafka and events.** To publish the audit log as events, set `CIMIENTO_OUTBOX_ENABLED=true` and create the topics, because automatic creation is off:

```bash
for t in cimiento.intake cimiento.intake.dlq cimiento.audit cimiento.audit.dlq; do
  cim exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 \
    --create --if-not-exists --topic "$t" --partitions 1 --replication-factor 1
done
```

**Observability.** `docker compose ... --profile observability up -d` starts the collector, Jaeger, Prometheus and Grafana. Grafana ships with anonymous read access: turn it off (`GF_AUTH_ANONYMOUS_ENABLED: "false"`) and do not publish its ports outside the server. Traces carry identifiers, counts and timings only, never document text.

## 12. Backups

All the information is in PostgreSQL. Daily backup:

```bash
mkdir -p /var/backups/cimiento
cim exec -T postgres pg_dump -U cimiento -Fc cimiento > /var/backups/cimiento/cimiento-$(date +%F).dump
```

Schedule it with `cron` and keep the copies off the server. Also keep `.env` somewhere safe and separate: without it you cannot start with the same secrets.

Restore into an empty database:

```bash
cim exec -T postgres pg_restore -U cimiento -d cimiento --clean --if-exists < cimiento-YYYY-MM-DD.dump
```

After restoring, check the audit chain:

```bash
curl -s -H "X-Api-Key: $CIMIENTO_ANALYST_KEY" http://127.0.0.1:8080/api/audit/verify
```

Test a restore at least once before relying on the backups.

## 13. Updating to a new version

1. Take a backup (section 12).
2. `git fetch && git checkout <new-version>`.
3. If you changed `default.conf.template` (section 8), check that the change is still there.
4. `cim up -d --build`.
5. Repeat the checks of section 7. New migrations run by themselves; issued certifications and the audit log are not changed.

## 14. Final checklist

- [ ] `.env` with random secrets, permissions 600 and a copy kept apart.
- [ ] Real people in `CIMIENTO_PEOPLE` and tokens handed over a secure channel.
- [ ] Only port 443 open to the outside; the cookie carries `Secure`.
- [ ] Company rates loaded in the core and in `tarifas.json`.
- [ ] Daily backup scheduled and one restore tested.
- [ ] `/api/audit/verify` says the chain is intact.
- [ ] A test tender analysed and reviewed by people of every role.
- [ ] Decided and written down whether a model is used and which data leave the company.

## 15. Common problems

| Symptom | Likely cause | Fix |
|---|---|---|
| The core does not start: `role keys still hold the .env.example placeholders` | Sample keys in `.env` | Generate random keys |
| The core does not start: `CIMIENTO_PEOPLE: tokenHash of ...` | Hash copied wrong | It must be the SHA-256 in hexadecimal (64 characters) |
| The console says the session expired | 8 hours passed, or the token changed | Sign in again |
| A button is disabled or the action returns 403 | The person's role does not allow it | See the roles table in the user manual |
| Proposals do not reach the inbox | Agents cannot reach the core or use the wrong key | Check `CORE_URL` and `CIMIENTO_AGENT_KEY` |
| "no consta" facts on real tenders | The keyless reader does not understand that format | Use a model and check quality on your own documents |
