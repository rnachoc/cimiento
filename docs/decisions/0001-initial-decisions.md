# ADR 0001: Initial decisions

Status: accepted
Date: 2026-11-18

## Context
Cimiento is a public portfolio project: a multi-agent system for construction tenders, scheduling and site progress. It must be easy to run by a stranger, honest about what it measures, and safe to publish.

## Decisions

### Stack
- Core: Java 17 (compile target), Spring Boot 3.5, Maven Wrapper. The core is the source of truth and holds the hard rules.
- Agents and MCP server: Python 3.12, managed by uv, linted with ruff, tested with pytest.
- Postgres 16 with pgvector (image `pgvector/pgvector:0.8.0-pg16`) for data and vectors.
- Kafka 3.9 in KRaft mode (image `apache/kafka:3.9.0`), no ZooKeeper.
- Review console: Angular (phase 8).
- All image and tool versions are pinned.

### Keyless mode
Without `ANTHROPIC_API_KEY` the agents use a deterministic fake model (recorded answers or a rule-based reader, labelled as such). With a key they use Claude. Results from each mode are always reported separately.

### Synthetic data only
All data is generated from a seed. There is a development split (`SEED=42`) and an evaluation split (`SEED=1337`) with held-out wording templates. Published numbers come only from the evaluation split. No real companies or people appear in the data.

### Development environment
The author works on Windows 11. `make` targets run in a Linux shell: WSL2 (Ubuntu) locally and `ubuntu-24.04` in CI. Docker Desktop provides the containers. If no local JDK is found, `make test` builds the core inside a `maven:3.9.11-eclipse-temurin-17` container.

### Secrets
No secrets in code. `.env.example` holds placeholders only; `.env` is git-ignored. gitleaks runs in pre-commit and in CI over the full history.

### Dependencies added in phase 0
- spring-boot-starter-web, spring-boot-starter-actuator (health endpoint), spring-boot-starter-test.
- pytest, ruff (dev only).
- pre-commit hooks: pre-commit-hooks, gitleaks.

## Consequences
- Contributors need Docker and a Linux shell. Native Windows `make` is not supported.
- Mockito is loaded as a Java agent in surefire, because runtime self-attach fails in some containers.
