# PROGRESS

Estado: fases 0 a 14 terminadas salvo la ejecución con Claude real de la fase 11 (herramienta lista, sin ejecutar) (2026-11-18).

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
Campos 266/390; citas verificadas 2226/2226 (incluidas las de obra); instrucciones detectadas 2/3, obedecidas 0, falsos positivos 0/30; escrituras indebidas 0; partidas exactas 380/380; presupuestos exactos 30/30; contradicciones 3/6 sin falsos positivos (tras el cambio de la fase 5).

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

## Fase 8: consola de revisión

### Qué se hizo
- Angular 22 (standalone, signals, sin zone.js): bandeja con portada y carruseles por tipo, detalle con hechos (Gantt con ruta crítica), reglas, citas, auditoría y formulario aprobar/corregir/rechazar. Diseño oscuro y cinematográfico pedido por el usuario; accesibilidad (teclado, foco, contraste, movimiento reducido).
- nginx: añade la clave REVIEWER y sobrescribe la del navegador; CSP estricta con nonce por petición para los estilos de Angular.
- Dockerfiles de núcleo y consola; perfil `app` en compose; `make e2e` y trabajo de CI con Chromium real.
- Verificado: 6 tests de componentes (Vitest); e2e 2/2 (aprobación ejecutada en el núcleo, 0 violaciones de CSP, clave falsificada rechazada con 403); capturas revisadas a mano (corregidos scroll, espaciado de reglas, cabecera móvil, formulario fijo que tapaba contenido). ADR 0009.
- Errores propios encontrados y corregidos: la limpieza del script e2e no apagaba la pila (ruta relativa tras `cd`).

### Deuda conocida
- Cualquiera que llegue a nginx actúa como REVIEWER (sin autenticación de personas).
- Node 24 instalado en `~/.local/node` dentro de WSL (sin sudo).

## Fase 9: eventos

### Qué se hizo
- Outbox transaccional en el núcleo (V4), publicador programado a `cimiento.audit` con clave por proyecto, al menos una vez; temas creados por el núcleo.
- Consumidores en Python (`confluent-kafka`) con reintentos de espera creciente, cola de errores con contexto en cabeceras y reproducción; puente `cimiento.intake` -> agentes -> núcleo; consumidores idempotentes.
- `make flow` y trabajo de CI: 12 comprobaciones con Kafka real (de la entrada a la decisión, cola de errores, reproducción, idempotencia). ADR 0010.
- Incidencia encontrada y corregida: el CI fallaba desde la fase 7 por una clave fija de test en MCP que gitleaks marca; ahora es aleatoria y la huella antigua está en `.gitleaksignore` como falso positivo. Debí vigilar ese CI al subir la fase 7.

### Deuda conocida
- El módulo Kafka de Testcontainers 1.21 no arranca bajo Docker Desktop en WSL; el test Java del outbox usa un productor simulado y el broker real se prueba en `make flow`.

## Fase 10: observabilidad y coste

### Qué se hizo
- OpenTelemetry con convenciones GenAI: traza por licitación, span por agente, por llamada al modelo (modelo y tokens) y por petición al núcleo; métricas de tokens, latencia y propuestas. Atributos validados como la telemetría (sin texto).
- Perfil `observability` (Collector, Prometheus, Jaeger, Grafana con panel aprovisionado) y `make observability`: 10 comprobaciones contra la pila real.
- `make cost-report`: tokens por modelo y coste solo para modelos con precio (USD de la lista oficial; euros solo si el usuario fija el tipo de cambio). Revisa también los spans contra todo el texto de los documentos: 3705 atributos, 0 con texto.
- CI: el trabajo `flow` ejecuta además `make cost-report` y la comprobación de observabilidad. ADR 0011.
- Incidencia corregida: `scripts/flow.sh` se subió sin permiso de ejecución y el trabajo `flow` del CI falló; ya pasa.
- Fallo de la fase 6 encontrado y corregido: las propuestas de obra citaban un resumen ("semana N, corte...") en vez de una línea literal del CSV, y la tasa de citas verificadas bajó al 94 %. Ahora citan la línea literal y el verificador lee el CSV; test de regresión que verifica cada cita de cada propuesta.

## Fase 11: evals completos y modelo real (en curso)

### Hecho
- `make scorecard`: une bid, planning, obra y búsqueda; compara con `evals/baseline.json` (línea base sin clave, tolerancia 0). Daños (instrucciones obedecidas, escrituras indebidas, citas falsas, afirmaciones sin fuente) deben ser 0; tests de que la puerta falla. Paso del CI.

### Pendiente (necesita al usuario)
- Ejecución con Claude real en 2 o 3 modelos. Requiere `ANTHROPIC_API_KEY` y un límite de gasto puesto antes en la consola de Anthropic.
- Estimación (aproximada, 3,5 caracteres por token): conjunto de evaluación, 33 licitaciones x 3 llamadas, unos 144 000 tokens de entrada por modelo. Opus 5.5: 3,81 a 10,28 USD (1,91 a 5,14 con Batch API); Sonnet 5.5: 1,91 a 5,14 (0,95 a 2,57); Haiku 5.5: 0,10 a 0,26 (0,05 a 0,13). Los tres: 5,81 a 15,67 USD (2,91 a 7,84 con Batch API).
- Batch API implementada (`make eval-real`, ensayo sin `CONFIRM=1`). El esquema de salida estructurada no se ha probado aún contra la API real.
- Cada usuario usa su propia clave en su `.env`; el repo no guarda ninguna.

## Fase 12: documentación y demo

### Qué se hizo
- `make demo` / `make demo-down`: datos, núcleo y consola en Docker, agentes sin clave sobre seis licitaciones del eval (E-012 con instrucción plantada detectada), claves de rol aleatorias en `.demo-keys` (ignorado por git), puertos 35432/38080/38088. Probado desde un clon limpio en WSL: 3 min con construcción de imágenes, consola HTTP 200, cadena de auditoría intacta, 0 contenedores tras `make demo-down`.
- docs/ARCHITECTURE.md reescrito en inglés con diagramas mermaid, reglas, tabla de enrutado, seguridad e índice de ADR.
- README final (inicio rápido con `make demo`, resultados solo sin clave, sección Agents with Claude), SECURITY.md revisado, guion del vídeo en docs/demo-video.md (lo graba el autor).

### Pendiente (necesita al usuario)
- Ejecución con Claude real (fase 11) con su clave y límite de gasto.
- Grabar el vídeo. Etiqueta v1.0.0 y hacer el repo público: decisión del autor.

## Fase 13: cartera de licitaciones y exportaciones

### Qué se hizo
- Núcleo: etapa por licitación (V5), reglas puras en `rules/Pipeline.java` (solo avanzar o descartar; motivo obligatorio para ofertar, descartar o perder; ofertar exige análisis del pliego aprobado; presentar exige nada pendiente de revisión). `POST /api/tenders/{id}/stage` solo REVIEWER, auditado (`TENDER_STAGE_CHANGED` sin texto libre); motivo en `tender_stage_change`. `GET /api/portfolio` calcula fecha de presentación y recomendación desde las propuestas. `GET /api/tenders/{id}/stages` da el historial.
- Exportaciones: planning a MS Project XML (MSPDI) y presupuesto a BC3 (FIEBDC-3, Windows-1252, CR LF), calculados con el CPM y las tarifas del núcleo. `GET /api/proposals/{id}/export`; las fallidas o rechazadas no se exportan; las pendientes llevan `-borrador`.
- Consola: vista Cartera (columnas por etapa, cuenta atrás, pendientes, motivo cuando hace falta, errores de reglas del núcleo), navegación Bandeja/Cartera, botones de descarga. MCP: herramienta `get_portfolio`.
- Tests: PipelineTest (5), ExportsTest (4), 2 de integración nuevos, 4 de consola, e2e ampliado (descarga del XML y cambio de etapa en un Chromium real). ADR 0012.

### Corrección posterior (v1.1.1)
- BC3 revisado contra la especificación FIEBDC-3/2016: ~K tenía 7 contadores de decimales en vez de 8 y faltaba el fin de archivo (ASCII 26). Ahora: ~V con fecha y tipo 2, ~K completo, raíz `OBRA##` con capítulo `C01#`, decimales exactos, códigos de 20 caracteres como máximo y resúmenes de 64. Tests nuevos con las líneas exactas del formato y la comprobación de totales que haría un lector. Sigue sin abrirse en Presto u otro programa.

### Ideas siguientes (no hechas)
- Consulta en lenguaje natural desde la consola (el agente de preguntas existe; falta servirlo con su búsqueda en Docker sin descargar 0,8 GB en la demo).
- Revisión previa a la presentación: lista de lo que pide el pliego frente a lo preparado.
- Aprender de las correcciones del revisor (guardar correcciones y aplicarlas en la siguiente licitación, medido con el eval).
- Riesgos de obra con propuesta de mitigación (plazo, coste, suministro) y informe de desviaciones.
- Exportar e importar datos de la empresa (tarifas, plantillas) y borrado de datos.

## Fase 14: ficha de licitación, seguimiento de obra y dirección

### Qué se hizo
- Núcleo: `rules/Site.java` (curva ponderada por duración, retraso por actividad con cota inferior si no ha empezado, alarma si supera la holgura, impacto con el CPM y si es cota inferior), `rules/Metrics.java` (etapas, tasa de éxito, importes, tiempo medio por etapa, carga de revisión, decisiones, vencimientos a 30 días). Endpoints `GET /api/tenders/{id}/site`, `GET /api/dashboard`, `GET /api/portfolio/{id}`. El `PROGRESS_UPDATE` admite `history` (semanas anteriores), validado por las reglas.
- Agentes: el avance semanal envía el historial de semanas.
- Consola: Ficha de licitación (`/licitaciones/:id`), Seguimiento de obra con curva S en SVG propio (`/obra/:id`), Dirección (`/direccion`); enlaces desde la cartera y el detalle; navegación Bandeja/Cartera/Dirección.
- Verificación: `SiteTest` contra la verdad terreno de las 66 obras (386 retrasos exactos, 400/402 alarmas, impacto exacto cuando no hay cotas inferiores), `MetricsTest`, 2 tests de integración, 6 de consola, e2e ampliado; revisión visual en escritorio y móvil sin errores de consola ni desbordamiento.

## Fase 15: personas, roles y separación de funciones

### Qué se hizo
- Núcleo: V6 (`person`, `person_session`, persona en `decision`, `tender_stage_change` y `audit_entry`, corrección pendiente en `proposal`). Tokens personales y sesiones guardados solo como SHA-256; cookie HttpOnly, SameSite=Strict, 8 h (`POST/DELETE /api/session`, `GET /api/session/me`). Personas desde `CIMIENTO_PEOPLE` (solo hashes).
- Reglas puras en `rules/Authority.java`: estudios (mediciones, presupuesto, análisis del pliego, memoria), jefatura de obra (planning, informes, avance, riesgos), gerencia (todo); importes sobre el umbral y paso a ofertar o descartar solo gerencia; cuatro ojos: `CORRECT` ya no ejecuta, deja la corrección pendiente y la aprueba otra persona (409 `SEGREGATION_OF_DUTIES` si lo intenta quien corrigió). Sin estado nuevo: sigue `PENDING_REVIEW`.
- Auditoría: el hash usa `ROL/usuario` si hay persona y solo el rol si no, así las filas antiguas verifican igual.
- Consola: pantalla de acceso, guard en todas las vistas, persona y rol en la cabecera, botones desactivados con el motivo, aviso de segunda aprobación, historial de decisiones con persona, cartera con movimientos reservados.
- Demo, e2e y flow generan tres personas ficticias (`scripts/people.sh`); `make demo` imprime los tokens. ADR 0013.
- Tests: `AuthorityTest` (4), `AuditHashTest` (2), 2 de integración nuevos y el de corrección ampliado, `session.spec.ts` (3) y 2 nuevos en el detalle; e2e en Chromium con corrección de jefatura de obra y aprobación de gerencia.

### Deuda conocida
- Sin proveedor de identidad (OIDC/SAML), sin bloqueo por intentos fallidos ni recuperación de token.
- La demo va por HTTP en localhost, así que la cookie no lleva `Secure` (sí detrás de HTTPS).

## Fase 18: certificaciones mensuales

### Qué se hizo
- Núcleo: `rules/Certification.java` (corte del mes en la última semana de avance que cierra dentro de él, a origen por partida = importe x % de su actividad, anterior = último certificado emitido, periodo, retención e IVA configurables, reglas que bloquean). V7: `certification` inmutable (trigger que rechaza UPDATE y DELETE). `GET /api/tenders/{id}/certification?month=`, `GET .../certifications`, `POST .../certifications` (jefatura de obra o gerencia; el núcleo recalcula y no acepta importes). Auditoría `CERTIFICATION_ISSUED` con la persona. MCP: `get_certifications` solo con totales.
- Consola: `/certificaciones/:id` (selector de mes, relación valorada por actividad, totales, motivos que bloquean, emitir con motivo si está desactivado, emitidas), enlazada desde la ficha y el seguimiento de obra.
- Tests: `CertificationTest` (10, con las 66 obras al céntimo), 1 de integración, 1 de autoridad, 3 specs de consola, 1 e2e en Chromium y 1 del MCP. ADR 0014.

### Deuda conocida
- Sin exportación a PDF ni BC3 de la certificación, sin revisión de precios ni facturación.
- Si cambia el presupuesto aprobado después de certificar, una partida puede quedar por debajo de lo certificado y el núcleo rechaza el mes.

## Deshacer cambios de etapa y importes en una línea

### Qué se hizo
- Núcleo: `Pipeline.revertTarget` y `checkRevert` (reglas puras), V8 (`tender_stage_change.revert`), `POST /api/tenders/{id}/stage/revert {note}`. Se puede deshacer el último cambio en revisión, decisión pendiente y preparando oferta, por quien lo hizo o por gerencia, con motivo; presentada y los estados finales no. El deshacer es un cambio nuevo auditado (`TENDER_STAGE_REVERTED`), no se borra nada, y no se puede deshacer un deshacer. ADR 0015.
- Consola: botón "Volver a …" en la cartera (con motivo) y marca "(deshecho)" en el historial. Los importes de Dirección y de la ficha van siempre en una línea con el símbolo del euro.
- Tests: 4 de reglas, 1 de integración (permisos, motivo, auditoría y cadena) y 2 specs de la cartera.

## Certificación en PDF

### Qué se hizo
- Núcleo: `service/CertificationPdf.java` dibuja la certificación emitida en A4 desde su foto inmutable, sin recalcular: empresa ficticia, número y mes, obra, corte, quién la emitió, relación valorada por actividad y partida, totales con retención, líquido, IVA y total a facturar. Pagina y numera las páginas. `GET /api/tenders/{id}/certifications/{number}/pdf`. Dependencia nueva: Apache PDFBox 3.0.3 (ADR 0016).
- Consola: enlace "PDF ↓" en cada certificación emitida.
- Tests: `CertificationPdfTest` (4), integración (tipo, nombre de archivo, 404) y 1 spec de consola.

### Deuda conocida
- Sin firma electrónica: el PDF es copia de lo emitido y auditado.

## Fase 16: calendario laboral con festivos

### Qué se hizo
- Reglas: `rules/Holidays.java` (diez festivos nacionales comunes a toda España, Viernes Santo por el algoritmo de Pascua) y `WorkCalendar` con instancias: estándar sin festivos (por defecto), nacionales de España, y festivos locales o autonómicos con nombre.
- Núcleo: V9 (`tender.calendar`, `tender.local_holidays`), `GET/POST /api/tenders/{id}/calendar` (jefatura de obra o gerencia; estudios no), auditoría `CALENDAR_CHANGED` sin los nombres. Lo usan el fin del planning y la regla de plazo, la vista del planning (se recalcula con el calendario actual), el seguimiento de obra y la exportación a MS Project (con los festivos como excepciones). ADR 0017.
- Consola: panel "Calendario laboral" en la ficha: selector, festivos locales, festivos dentro de la obra y cuánto mueven el fin.
- Tests: 4 de reglas (Pascua, festivos de 2027, el calendario estándar igual al de siempre en 600 días, festivos en laborable), 1 de exportación, 1 de integración y 4 de consola.

### Deuda conocida
- Los festivos que caen en domingo no se trasladan (depende de cada comunidad). Sin medias jornadas, horario de verano ni calendarios por actividad.
- Las excepciones del XML de MS Project no se han abierto en MS Project.

## Siguiente paso
Hechas la 15, la 16, la 18, el deshacer y el PDF de certificación. Quedan la 17 (modificados) y la 19 (revisión previa a la presentación). Quedan sin hacer, por decisión del autor, la 16 (calendario laboral en el CPM, revisando antes `rules/WorkCalendar.java`), la 17 (modificados) y la 19 (revisión previa a la presentación). La ejecución real con Claude sigue aparcada.
