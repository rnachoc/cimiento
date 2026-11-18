# Fase 3: ingesta y búsqueda con citas

Lee CLAUDE.md, PLAN.md y PROGRESS.md antes de empezar. Trabaja en modo plan: primero propón la lista de ficheros, las pruebas y los riesgos, y espera mi aprobación.

## Tarea
En services/agents: ingesta de PDF y texto a fragmentos por cláusula y página, índice léxico (texto completo de Postgres con configuración `spanish` o BM25 ligero en Python) y vectores en pgvector con embeddings locales multilingües (fastembed) y reordenador local multilingüe. Endpoint POST /search que devuelve fragmentos con documento y página y el rango en cada etapa. Genera preguntas sintéticas desde la verdad terreno y mide hit@1 y hit@5 de cada método (léxico, vectores, híbrido, reordenado). Elige el método por medición en desarrollo, publica la medición en evaluación, y escribe un ADR. Mide también la tasa de "no consta" correcto.

## Criterios de aceptación
Informe `make eval-retrieval` con la tabla por método y por tipo de pregunta; el sistema dice que no consta cuando la respuesta no está en los documentos; los resultados se publican tal cual, con la advertencia de que las preguntas son sintéticas.

## Notas
Esfuerzo alto si se atasca. No descargues modelos en CI sin caché.

## Al terminar
Ejecuta `make test`, actualiza PROGRESS.md (qué se hizo, decisiones, deuda conocida, siguiente fase), haz commit con Conventional Commits y para. No empieces la siguiente fase.
