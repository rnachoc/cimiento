# PROGRESS

Estado: fase 1 terminada (2026-11-18). Siguiente: fase 2.

## Fase 0: repo base

### Qué se hizo
- Monorepo: services/core (Spring Boot 3.5.7, Java 17, Maven Wrapper), services/agents y services/mcp (Python 3.12 con uv, ruff, pytest), placeholders en web/console, data y evals.
- deploy/docker-compose.yml: Postgres 16 con pgvector 0.8.0 (extensión creada al iniciar) y Kafka 3.9.0 en KRaft, con healthchecks. Listener interno `kafka:19092`, externo `localhost:9092`.
- Makefile: help, up, down, test, lint; demo, eval y scorecard avisan de la fase en que llegan.
- CI (.github/workflows/ci.yml): lint, test y gitleaks sobre todo el historial.
- pre-commit: gitleaks y comprobaciones básicas.
- ADR 0001, LICENSE (MIT), SECURITY.md, .env.example con marcadores, .gitattributes (LF).

### Decisiones
- Ejecución de `make` en WSL2 (Ubuntu); en CI, ubuntu-24.04.
- `CORE_JDK=auto` usa el JDK local; sin él (o con `CORE_JDK=docker`) compila en `maven:3.9.11-eclipse-temurin-17` como el usuario del host.
- Mockito se carga como agente Java en surefire (el auto-attach falla en contenedores).
- Kafka sin creación automática de topics: se crearán explícitamente en la fase 9.
- private/ está fuera de git (notas no publicables).

## Fase 1: generador de datos con verdad terreno

### Qué se hizo
- `data/generator` (paquete `cimiento-data`): 30 licitaciones por conjunto (10 pequeñas, 10 medianas, 10 grandes) con pliego, anexo y mediciones en PDF y texto paginado, tarifas propias, avance semanal y partes diarios, verdad terreno en JSON y manifiesto con sha256.
- Casos plantados: 3 instrucciones ocultas con su gemelo limpio (4 ubicaciones, español e inglés), 6 contradicciones de plazo pliego/anexo, 9 partidas duplicadas, unidades mezcladas (km, ha y variantes de escritura), 3 plannings que superan el plazo, 3 preguntas sin respuesta por licitación, retrasos críticos y no críticos (con y sin alarma).
- Conjuntos: desarrollo (`SEED=42`, ids D-) y evaluación (`SEED=1337`, ids E-, con plantillas `eval_only`).
- Resolutor CPM de referencia (`solver.py`) contrastado con `networkx.dag_longest_path_length` en 300 grafos aleatorios y con casos a mano.
- `make data` (ambos conjuntos, o `SPLIT=` / `SEED=`), data/README.md con el formato, ADR 0002, definiciones nuevas en docs/SYNTHETIC_DATA.md (duplicadas, plazo, alarma, identificadores).
- 79 tests. Verificado: `make test` y `make lint` salen con 0; mutaciones plantadas a propósito (página errónea, precio erróneo, fecha no fija, regla de alarma) hacen fallar los tests.

### Decisiones
- fpdf2 con fuentes estándar (Latin-1): "euros"/"EUR" en vez del símbolo €.
- Una sola maquetación para PDF y texto; una cláusula nunca se parte entre páginas.
- El test de reproducibilidad regenera en otro proceso con otro `PYTHONHASHSEED`.
- Retrasos de 1 a 6 días laborables, modelados como inicio tardío a ritmo planificado.
- Datos generados fuera de git (`data/generated/`).

### Deuda conocida
- Las ubicaciones de las filas de mediciones son aleatorias (p. ej. excavación en "Planta primera"); no afecta a las métricas.
- Documentos de plantilla: las variantes reservadas reducen el sobreajuste, no lo eliminan.
- Repo en /mnt/c: E/S lenta desde WSL (`make data` tarda unos 45 s; 15 s por conjunto en disco Linux).
- Hook de pre-commit no instalado en `.git/hooks` (requiere `pipx install pre-commit && pre-commit install` en WSL).
- Para lanzar comandos de WSL desde Windows hay que usar `wsl.exe -e`: con `wsl.exe --` el shell expande `$?` antes de tiempo.

## Siguiente paso
Fase 2: ejecutar prompts/02-nucleo-java.md
