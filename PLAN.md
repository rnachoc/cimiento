# Plan de desarrollo: Cimiento

Proyecto: sistema multiagente de IA para construcción (licitaciones, planificación, seguimiento de obra y preguntas sobre documentos), con datos sintéticos, publicado en github.com/rnachoc/cimiento.

## Cómo trabajar
- Una sesión por fase. Empieza con `/clear`, pega el prompt de `prompts/NN-*.md` y trabaja en modo plan primero.
- `PROGRESS.md` es la memoria: al acabar cada fase se actualiza. La siguiente sesión empieza leyéndolo.
- Commit al final de cada fase; el autor revisa el diff antes de empujar.
- Sin clave de Anthropic todo funciona con el modelo falso determinista. Con clave solo en la fase 11 (cuesta dinero: límite de gasto antes).
- Entorno de desarrollo: Windows 11 con Docker Desktop. Los comandos `make` se ejecutan desde WSL2 o Git Bash (se decide y documenta en la fase 0).

## Alcance (ciclo completo)
1. Licitaciones: leer pliego y anexos, extraer requisitos, plazos, criterios y penalizaciones; mediciones por oficio; presupuesto con tarifas propias; riesgos; informe bid/no-bid; borrador de memoria técnica.
2. Planificación: actividades, duraciones, dependencias y ruta crítica, validadas por el núcleo.
3. Obra: avance contra planning, desviaciones, partes diarios, RFIs, informes semanales y actas.
4. Preguntas en lenguaje natural sobre los documentos, con cita a documento y página.
5. Revisión humana: consola donde una persona aprueba, corrige o rechaza.

## Fuera de alcance (decláralo en el README)
Lectura de planos CAD o imagen, integración real con MS Project o Primavera, datos reales, multiusuario con cuentas reales.

## Principios de medición (valen para todas las fases)
- Dos conjuntos: desarrollo (`SEED=42`) y evaluación (`SEED=1337`, generado con variantes de redacción reservadas). Los números publicados salen solo del conjunto de evaluación.
- Ningún agente lee la verdad terreno. Un test lo impide.
- Con 30 licitaciones se publican recuentos (p. ej. 27/30) además de porcentajes.
- Cada métrica tiene una definición operativa en docs/SYNTHETIC_DATA.md antes de medirse.

## Fases (en orden, cada una acaba con tests verdes y commit)

| Fase | Entrega | Criterio de aceptación | Tiempo orientativo |
|---|---|---|---|
| 0 | Repo, Makefile, CI, pre-commit con gitleaks, ADR 0001, PROGRESS.md, LICENSE MIT, SECURITY.md, docker-compose con Postgres (pgvector) y Kafka | `make up` y `make test` pasan; CI verde | 0,5 día |
| 1 | Generador de datos sintéticos con semilla, variantes de redacción, gemelos con y sin inyección, conjuntos de desarrollo y evaluación, verdad terreno | `make data` reproducible; tests del generador | 1,5 días |
| 2 | Núcleo Java: dominio, reglas, CPM, tabla de enrutado, auditoría encadenada por hash, roles por API key, Flyway | Tests de reglas y CPM; el motor coincide con el resolutor de referencia en las 60 licitaciones | 2 días |
| 3 | Ingesta y búsqueda (léxica + vectores multilingües + reordenador) con citas a documento y página | hit@1 y hit@5 medidos y publicados; `POST /search`; "no consta" medido | 1,5 días |
| 4a | Infraestructura de agentes: interfaz de modelo, modelo falso, adaptador Claude con grabación, coordinador, cliente del núcleo, arnés de evaluación, telemetría sin contenido, bid analyst | `make eval-bid` (extracción, citas, inyecciones) | 2 días |
| 4b | Takeoff, cost estimator, risk analyst, proposal writer, Q&A, informe bid/no-bid | `make eval-bid` completo; 0 inyecciones obedecidas; 0 escrituras indebidas | 2 días |
| 5 | Agente planificador + validación del núcleo | Planning válido en todas las licitaciones; error de duración y ruta crítica medidos | 1 día |
| 6 | Obra: agente de avance, informes (parte diario, RFI, semanal, acta) | Desviaciones detectadas contra la verdad terreno; informes citan sus fuentes | 1,5 días |
| 7 | Servidor MCP de solo lectura | Un asistente puede listar proyectos, leer un planning y buscar en documentos; el núcleo rechaza escrituras | 0,5 día |
| 8 | Consola de revisión (Angular) | Un navegador real aprueba una propuesta en CI (Playwright) | 2,5 días |
| 9 | Eventos: outbox hacia Kafka, consumidores con reintentos y cola de errores | Un flujo completo licitación a decisión viaja por Kafka en CI | 1 día |
| 10 | OpenTelemetry (trazas y métricas), panel de Grafana, informe de coste | Trazas sin contenido; `make cost-report` | 1 día |
| 11 | Evals completos, línea base comprometida, puerta de CI, ejecución con modelo real (Batch API) | `make scorecard`; tabla con Claude real en el README; grabaciones para la demo | 1,5 días |
| 12 | README, arquitectura, limitaciones, vídeo corto de demo (lo graba el autor), release v1.0.0 | Un desconocido arranca `make demo` sin clave y ve resultados | 1 día |

Total: unos 19 días de trabajo. Opcional después: Temporal y Helm con kind.

## Qué se publica y cómo se cuenta
- README en inglés, con una sección "Known limitations" y los resultados separados en "keyless baseline" y "with Claude".
- Mientras no esté acabado, se presenta como "en curso" con la fase real. No más.
