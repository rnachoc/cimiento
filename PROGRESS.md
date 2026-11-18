# PROGRESS

Estado: fases 0 a 7 terminadas (2026-11-18). Siguiente: fase 8.

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

## Fase 2: núcleo Java

### Qué se hizo
- Dominio y Flyway: proyecto, licitación, tarifa, propuesta, clave de idempotencia, decisión, planning (actividades y enlaces), avance y auditoría.
- Motor de reglas en Java puro con orden fijo (PAYLOAD, CITATIONS, TAKEOFF_UNITS_AND_SUMS, BUDGET, SCHEDULE_GRAPH, CRITICAL_PATH, CONTRACT_TERM, REVIEW_THRESHOLD) y tabla de enrutado.
- API REST con claves por rol (AGENT propone, REVIEWER decide, ANALYST solo lee) y lista permitida; todo lo demás se deniega.
- Auditoría append-only (triggers) con hash encadenado y `GET /api/audit/verify`; idempotencia por `Idempotency-Key`.
- ADR 0003 con decisiones y casos límite.
- Tests: 178 en el núcleo. CPM a mano y contra camino más largo por fuerza bruta (500 grafos), reglas una a una, 133 casos contra la verdad terreno de las 66 licitaciones (60 más 6 gemelos: ruta crítica, fechas, holguras, plazo, mediciones y presupuesto coinciden), 9 de integración con Testcontainers y Postgres real.
- Verificado además: arranque real contra el Postgres de compose (health, 201/401, auditoría íntegra) y que el servicio no arranca con las claves de ejemplo. Mutaciones (analista con escritura, corrección sin revalidar) hacen fallar los tests.

### Decisiones
- El plazo se comprueba después del CPM porque necesita la duración (el prompt lo listaba antes).
- El núcleo comprueba que las citas existen y están bien formadas; comprobar el texto en la página se hace en agentes (fase 4).
- Sin Spring Security: un filtro y una tabla de permisos.
- Testcontainers con `api.version=1.44` (Docker 29 rechaza la versión por defecto).
- `make test-core` genera los datos si faltan; ya no hay compilación del núcleo en contenedor (necesita JDK 17+ y Docker).

### Deuda conocida
- Las claves por rol identifican el rol, no a la persona: todas las decisiones constan como REVIEWER.
- Sin paginación en los listados (`/api/proposals`, `/api/audit`).

## Fase 3: ingesta y búsqueda

### Qué se hizo
- Ingesta de PDF (pypdf, sin pies de página) y texto en fragmentos por cláusula y página; mismos fragmentos desde PDF y texto (test).
- Cuatro métodos: BM25 propio, vectores (MiniLM multilingüe en pgvector, base propia `cimiento_search`), híbrido RRF y reordenador bge-reranker-v2-m3-int8. `POST /search` con el rango de cada etapa y "no consta".
- `make eval-retrieval`: preguntas sintéticas desde la verdad terreno, redacción distinta en dev y eval, método y umbrales elegidos en dev. ADR 0004.
- Tests sin descargar modelos (embedder por hash y reordenador por solapamiento, nunca usados para medir calidad); pgvector comparado con la versión en memoria contra un Postgres real.

### Resultados (eval, modelos locales, sin LLM)
hit@1 / hit@5: léxico 67,9 / 75,3 %; vectores 64,5 / 91,4 %; híbrido 70,4 / 83,4 %; reordenado 84,8 / 90,9 % (elegido en dev). "No consta": 89/90 sin respuesta marcadas, pero 206/770 con respuesta también marcadas (umbral de dev demasiado alto para la redacción nueva).

### Deuda conocida
- Umbral de "no consta" que no generaliza; la evaluación tarda 1 a 2 horas en CPU (reordenador).

## Fases 4a y 4b: agentes de licitación

### Qué se hizo
- Capa de modelo con fuente etiquetada: Claude (SDK oficial, salida estructurada, caché del prompt de sistema, grabación con `CIMIENTO_RECORD=true`), respuestas grabadas, lector por reglas sin clave.
- Coordinador en código; cliente del núcleo con lista permitida; telemetría que rechaza texto; citas verificadas en la página; detector de instrucciones escrito solo con dev.
- Agentes: analista de pliego, mediciones, presupuesto (lo calcula el núcleo), riesgos, bid/no-bid con reglas, borrador de memoria técnica, preguntas y respuestas con cita o "no consta".
- `make eval-bid` con informe en `evals/reports/bid.md`. ADR 0005.
- Verificado: 46 tests de agentes, prueba de punta a punta contra el núcleo real (11 propuestas aceptadas, ninguna falla reglas, auditoría íntegra).

### Resultados (eval, línea base sin clave)
Campos 266/390; citas verificadas 1805/1805; instrucciones detectadas 2/3, obedecidas 0, falsos positivos 0/30; escrituras indebidas 0; partidas exactas 380/380; presupuestos exactos 30/30; contradicciones 3/6 sin falsos positivos (tras el cambio de la fase 5).

### Decisiones
- Sin `fallbacks` del servidor: una respuesta de otro modelo falsearía la comparación de modelos.

### Deuda conocida
- El lector por reglas no generaliza a redacciones nuevas (68 % en eval frente a 100 % en dev).
- Las recomendaciones bid/no-bid no tienen verdad terreno.

## Fase 5: planificador

### Qué se hizo
- `planner.py`: actividades desde las partidas medidas y la tabla de tarifas; duración calculada en código; tabla de dependencias típicas; el modelo solo elige cuadrillas y enlaces (sin clave, heurística por días-cuadrilla fijada en dev).
- El coordinador envía una propuesta SCHEDULE; el núcleo valida, calcula la ruta crítica y compara con el plazo.
- `make eval-plan`: validez y ruta crítica con el resolutor de referencia del generador. ADR 0006.
- Cambio de diseño detectado por la evaluación: si el lector no encontraba el plazo, el núcleo no registraba la licitación y no corría nada. Ahora el plazo es opcional en el núcleo (migración V3); `CONTRACT_TERM` avisa "plazo desconocido" y fuerza revisión humana. No cambia ninguna regla de lectura.

### Resultados (eval, línea base sin clave)
Plannings válidos 30/30; duración exacta 30/30; ruta crítica idéntica 30/30; aviso de plazo coincide 30/30. Esperable por construcción (mismas reglas que el generador); documentado en README.

### Deuda conocida
- La métrica del planificador mide acuerdo con las reglas del generador, no habilidad de planificación.

## Fase 6: obra e informes

### Qué se hizo
- `progress.py`: SPI, retraso (cota inferior si la actividad no ha empezado), holgura, alarma e impacto con CPM; propuesta PROGRESS_UPDATE.
- `reports.py`: parte diario, RFI (huecos y contradicciones), informe semanal y acta; cada frase con fuentes y `check_sources` que rechaza afirmaciones sin fuente. Siempre borradores.
- `make eval-site`. ADR 0007. Prueba de punta a punta contra el núcleo real: 21 propuestas aceptadas, auditoría íntegra.
- Consola (fase 8): el usuario pide diseño muy llamativo e interactivo (referencias Apple, Netflix, HBO, Kick), sin copiar marcas; prompt 08 actualizado.

### Resultados (eval, sin clave)
Retrasos detectados 42/42, falsos 0; alarmas 182/184; días exactos 32/32; impacto exacto 26/30; 90 informes con fuentes.

### Deuda conocida
- Retraso de actividades no iniciadas solo como cota inferior (10 casos en eval); de ahí los fallos de impacto.

## Fase 7: servidor MCP de solo lectura

### Qué se hizo
- `services/mcp` con el SDK oficial (`mcp` 2.x; `FastMCP` pasó a llamarse `MCPServer`). Ocho herramientas de lectura anotadas como solo lectura; ninguna de escritura.
- Cliente solo GET con clave ANALYST; enmascarado de precios, rendimientos e instrucciones plantadas.
- Tests con núcleo simulado (lecturas, anotaciones, enmascarado, escrituras rechazadas en cliente) y prueba contra el núcleo real: lectura completa por MCP y 403 en cada POST con la clave ANALYST. ADR 0008. Ejemplo de configuración en el README.

## Siguiente paso
Fase 8: ejecutar prompts/08-consola.md, con el diseño llamativo pedido .
