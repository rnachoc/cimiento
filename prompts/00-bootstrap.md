# Fase 0: repo base

Lee CLAUDE.md, PLAN.md y PROGRESS.md antes de empezar. Trabaja en modo plan: primero propón la lista de ficheros, las pruebas y los riesgos, y espera mi aprobación.

## Tarea
Crea el esqueleto del monorepo: services/core, services/agents, services/mcp, web/console, data, evals, docs/decisions, deploy. Makefile con targets help, up, down, test, lint, data, demo, eval, scorecard. docker-compose con Postgres (pgvector) y Kafka (KRaft). GitHub Actions: lint, tests y gitleaks sobre todo el historial. pre-commit con gitleaks y comprobaciones básicas. ADR 0001: decisiones iniciales (stack, modo sin clave, datos sintéticos). Crea PROGRESS.md. .env.example con valores de marcador. LICENSE (MIT) y SECURITY.md desde el primer commit. .gitignore que excluya private/. Decide y documenta en el ADR cómo se ejecuta `make` en Windows (WSL2 o Git Bash) y fija las versiones: Java 17 como objetivo de compilación, Python 3.12 gestionado por uv.

## Criterios de aceptación
`make up` arranca Postgres y Kafka; `make test` pasa (aunque sea con un test trivial por servicio); el CI es verde; gitleaks no encuentra nada.

## Notas
Sin lógica de negocio todavía. Un commit por cambio.

## Al terminar
Ejecuta `make test`, actualiza PROGRESS.md (qué se hizo, decisiones, deuda conocida, siguiente fase), haz commit con Conventional Commits y para. No empieces la siguiente fase.
