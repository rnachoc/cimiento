# Fase 9: eventos y flujos

Lee CLAUDE.md, PLAN.md y PROGRESS.md antes de empezar. Trabaja en modo plan: primero propón la lista de ficheros, las pruebas y los riesgos, y espera mi aprobación.

## Tarea
Outbox transaccional en el núcleo que publica la auditoría en Kafka (clave por proyecto, sin datos sensibles); consumidores con reintentos con espera creciente y cola de errores. Un puente convierte eventos en el siguiente paso del flujo licitación a decisión. Temporal es opcional: si se añade, ADR aparte.

## Criterios de aceptación
Un flujo completo viaja por Kafka en CI y acaba en decisión; los fallos de consumidor van a la cola de errores y se pueden reproducir.

## Notas
Orden por clave, entrega al menos una vez, consumidores idempotentes.

## Al terminar
Ejecuta `make test`, actualiza PROGRESS.md (qué se hizo, decisiones, deuda conocida, siguiente fase), haz commit con Conventional Commits y para. No empieces la siguiente fase.
