# PROGRESS

Estado: fase 0 terminada (2026-11-18). Siguiente: fase 1.

## Fase 0: repo base

### Qué se hizo
- Monorepo: services/core (Spring Boot 3.5.7, Java 17, Maven Wrapper), services/agents y services/mcp (Python 3.12 con uv, ruff, pytest), placeholders en web/console, data y evals.
- deploy/docker-compose.yml: Postgres 16 con pgvector 0.8.0 (extensión creada al iniciar) y Kafka 3.9.0 en KRaft, con healthchecks. Listener interno `kafka:19092`, externo `localhost:9092`.
- Makefile: help, up, down, test, lint; data, demo, eval y scorecard avisan de la fase en que llegan.
- CI (.github/workflows/ci.yml): lint, test y gitleaks sobre todo el historial.
- pre-commit: gitleaks y comprobaciones básicas.
- ADR 0001, LICENSE (MIT), SECURITY.md, .env.example con marcadores, .gitattributes (LF).
- Verificado en local: `make up` deja los dos contenedores sanos; `make test` y `make lint` pasan; `pre-commit run --all-files` pasa; gitleaks sobre el historial no encuentra nada.

### Decisiones
- Ejecución de `make` en WSL2 (Ubuntu); en CI, ubuntu-24.04.
- Sin JDK local, `make test` compila el núcleo en un contenedor `maven:3.9.11-eclipse-temurin-17`.
- Mockito se carga como agente Java en surefire (el auto-attach falla en contenedores).
- Kafka sin creación automática de topics: se crearán explícitamente en la fase 9.
- private/ está fuera de git (notas no publicables).

### Deuda conocida
- CI no verificado todavía: falta empujar a GitHub.
- JDK 17 sin instalar en WSL; mientras tanto se usa el contenedor (más lento y deja `services/core/target` con propietario root en WSL).
- El hook de pre-commit no está instalado en `.git/hooks`: requiere `pipx install pre-commit && pre-commit install` en WSL.
- Repo en /mnt/c: E/S lenta desde WSL para Maven y uv.

## Siguiente paso
Fase 1: ejecutar prompts/01-datos-sinteticos.md
