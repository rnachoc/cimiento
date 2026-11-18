# ADR 0003: Core service (rules, routing, audit, roles)

Status: accepted
Date: 2026-11-18

## Context
The core is the source of truth. Models propose; the core validates against deterministic rules, decides the route (automatic or a person), executes and audits. Hard rules live here, with tests.

## Decisions

### Rule engine in plain Java, fixed order
`dev.cimiento.core.rules` has no Spring or database code, so every rule is unit tested. Order:

1. `PAYLOAD`: the payload parses into the type's schema (unknown fields rejected) and ranges hold.
2. `CITATIONS`: at least one citation with document, page >= 1 and text. Nothing runs without one.
3. `TAKEOFF_UNITS_AND_SUMS`: known unit spellings, rows convert to the item's canonical unit (from the company rate), non-duplicate rows add up to the item quantity (tolerance 0.01), and a row marked duplicate must repeat an earlier row.
4. `BUDGET`: amount = quantity x company price, rounded to cents half up; a claimed total must match. The budget is computed only here.
5. `SCHEDULE_GRAPH`: positive durations, known activities, no duplicates, no self links, no cycles.
6. `CRITICAL_PATH`: CPM with lexicographic topological order, so results do not depend on input order.
7. `CONTRACT_TERM`: schedule end against `start + N months - 1 day`; exceeding it is a WARN that forces review.
8. `REVIEW_THRESHOLD`: budget at or above `cimiento.review.amount-threshold-eur` (default 1,000,000), an `INJECTION_DETECTED` signal, a high risk signal, an injection risk signal, or progress behind plan.

The phase prompt lists the term check before the graph check. The term check needs the CPM duration, so it runs after it.

The core checks that citations exist and are well formed. Checking that the cited text is on the cited page needs the documents, which live in the agents service; that check is done there (phase 4) and the core trusts only its own rules for execution.

### Routing table
| Type | Default route |
|---|---|
| TAKEOFF, BUDGET, SCHEDULE, BID_ANALYSIS, DOCUMENT_DRAFT, REPORT | person |
| RISK_SIGNAL, PROGRESS_UPDATE (records without external effect) | automatic, unless a rule warns or a threshold applies |

Any FAIL gives `RULES_FAILED` and nothing runs. A decision runs the rules again on what will be executed (the corrected payload for CORRECT); if they fail the decision is refused (422) and nothing changes.

### Roles by API key, without Spring Security
A servlet filter maps `X-Api-Key` to AGENT, ANALYST or REVIEWER with constant-time comparison and applies an allow list (method + path). Anything not listed is denied. AGENT proposes and registers tenders; REVIEWER decides and creates projects; ANALYST only reads. Keys come from the environment; the service refuses to start with missing, short (< 16), repeated or placeholder keys. Spring Security was not added: one filter and one table are easier to read and test for three fixed roles.

### Audit chain
`audit_entry` is append-only: database triggers reject UPDATE, DELETE and TRUNCATE. Each entry stores `sha256(prev_hash, created_at, actor, action, entity type, entity id, details)`; `GET /api/audit/verify` recomputes the chain. Appends take a transaction-scoped advisory lock so the chain stays linear under concurrency. Details hold identifiers, statuses and rule outcomes, never document text. Timestamps are truncated to microseconds so they survive the round trip through Postgres.

### Idempotency
`POST /api/proposals` requires `Idempotency-Key`. The key is stored per role with a hash of the canonical request: the same key and body returns the first result (`Idempotent-Replay: true`); the same key with another body is a 409. Projects and tenders are idempotent by code.

### Dependencies added
- spring-boot-starter-data-jpa, flyway-core, flyway-database-postgresql, postgresql: persistence and migrations.
- spring-boot-starter-validation: request validation.
- spring-boot-testcontainers, testcontainers junit-jupiter and postgresql (test): integration tests against the same pgvector image as docker compose.

### Testcontainers and Docker Engine 29
Docker Engine 29 rejects the old default API version of the docker-java client in Testcontainers 1.21.x. `src/test/resources/docker-java.properties` sets `api.version=1.44` (Docker 25 and later).

## Edge cases documented by tests
- Two parallel critical paths: all their activities are critical.
- An activity without successors floats to the project end.
- Month-end start dates clamp (31 January + 1 month = 28 February).
- A planned start that is not a Monday fails the term check.
- One cent of tolerance in takeoff sums; none in budget totals.

## Consequences
- Building the core needs a local JDK 17+ and Docker.
- The core engine matches the independent reference solver on all 66 generated tenders (60 plus 6 twins): durations, early and late dates, float, critical set and term flag; takeoff sums and budget totals also match.
