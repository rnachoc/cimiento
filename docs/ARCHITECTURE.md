# Arquitectura propuesta

```
 documents (pliegos, anexos, mediciones, partes)  ->  ingestion (Python)
        |                                                   |
        v                                                   v
   object store (S3 API)  <---  agents (Python, FastAPI) ---> search (lexical + pgvector + rerank)
                                   |  propose via tools
                                   v
                           core (Java, Spring Boot)  <---  review console (Angular)
                        rules, CPM, audit chain, roles
                                   |
                       outbox -> Kafka -> consumers
                                   |
                      OpenTelemetry -> Grafana / Jaeger
        MCP server (read-only) exposes projects, schedules and search to an assistant
```

## Servicios
- services/core (Java 17): fuente de verdad. Proyectos, licitaciones, propuestas de agentes, planning, avance, auditoría. Aquí viven las reglas duras.
- services/agents (Python): ingesta, búsqueda, agentes y coordinador. Sin acceso directo a la base de datos del núcleo: solo por su API con una clave de rol AGENT.
- services/mcp (Python): servidor MCP de solo lectura con clave de rol ANALYST.
- web/console (Angular): bandeja de propuestas que requieren persona; aprobar, corregir, rechazar.

## Agentes
Coordinador en código (no un LLM) que decide qué agentes llamar y en qué orden. Agentes con salida estructurada (Pydantic) y herramientas limitadas:
1. Bid analyst: requisitos, plazos, criterios de adjudicación, penalizaciones, solvencia. Cita documento y página.
2. Takeoff analyst: mediciones por oficio desde la memoria de mediciones. El código suma y comprueba unidades.
3. Cost estimator: asigna cada partida a una tarifa de la empresa (tabla propia). El modelo solo elige la tarifa; el núcleo calcula el presupuesto (única fuente del cálculo).
4. Risk analyst: contradicciones entre documentos, plazos imposibles, cláusulas abusivas. Cada señal con evidencia citada que el código vuelve a comprobar.
5. Proposal writer: memoria técnica en la plantilla de la empresa. Borrador, siempre revisado por persona.
6. Construction planner: actividades, duraciones, dependencias. El núcleo valida (sin ciclos, duraciones positivas) y calcula la ruta crítica.
7. Progress analyst: avance semanal contra planning, desviaciones, impacto en ruta crítica.
8. Report writer: parte diario, RFI, informe semanal, acta. Cada frase referencia un registro de origen.
9. Q&A: respuestas con cita a documento y página; si no hay soporte, lo dice.
10. Bid/no-bid: informe que reúne las salidas anteriores. La recomendación la calcula el código con reglas explícitas; el modelo solo redacta. Siempre revisado por persona.

Cada agente tiene una lista permitida de llamadas al núcleo. Ninguno lee la verdad terreno (test que lo impide).

## Tabla de enrutado (núcleo)
Se fija en la fase 2, en código y con tests. Por defecto: toda propuesta de licitación, presupuesto, planning, informe o bid/no-bid va a revisión humana. Solo se ejecutan solas las operaciones de registro sin efecto externo (p. ej. guardar un fragmento indexado o una señal de riesgo como "pendiente"). Cualquier señal de inyección fuerza revisión.

## Reglas del núcleo (con tests)
Definiciones de ruta crítica y cita en docs/SYNTHETIC_DATA.md. Suma de mediciones y unidades coherentes; presupuesto = mediciones por tarifa; plazo del planning frente al plazo contractual; grafo de dependencias acíclico; ruta crítica determinista; umbral de importe o riesgo que fuerza revisión humana; nada se ejecuta sin cita; auditoría encadenada por hash; operaciones idempotentes por clave.

## Seguridad
Inyección de instrucciones en documentos (conjunto de evaluación con casos plantados); roles por clave (AGENT no puede aprobar; ANALYST solo lee; REVIEWER aprueba); datos sintéticos, sin personales; secretos fuera del código; telemetría sin contenido desde la fase 4a (el logger y los spans solo aceptan identificadores, recuentos y tiempos).

## Búsqueda
Léxica con la búsqueda de texto completo de Postgres (configuración `spanish`) o un BM25 ligero en Python; vectores en pgvector. Sin servicios extra salvo que una medición lo justifique. Los documentos están en español: embeddings y reordenador multilingües, con caché de modelos en CI.

## Modo sin clave
Modelo falso determinista: reproduce respuestas grabadas o usa un lector basado en reglas etiquetado como tal. Con `ANTHROPIC_API_KEY` usa Claude y `CIMIENTO_RECORD=true` graba las respuestas para repetir la demo gratis. Las grabaciones llevan modelo y fecha, y la demo las muestra etiquetadas como tales; nunca se mezclan con la línea base sin clave.
