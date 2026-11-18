# Fase 2: núcleo Java y Spring Boot

Lee CLAUDE.md, PLAN.md y PROGRESS.md antes de empezar. Trabaja en modo plan: primero propón la lista de ficheros, las pruebas y los riesgos, y espera mi aprobación.

## Tarea
Crea services/core: modelo de dominio (proyecto, licitación, propuesta, planning, actividad, avance, decisión), Flyway, JPA, API REST con claves por rol (AGENT, ANALYST, REVIEWER). Motor de reglas en orden fijo: unidades y sumas de mediciones, presupuesto = mediciones por tarifa, plazo frente al contractual, grafo acíclico, ruta crítica (CPM) determinista, umbral de importe o riesgo que fuerza revisión, tabla de enrutado (docs/ARCHITECTURE.md) y lista permitida de llamadas por rol. El presupuesto se calcula solo aquí. Auditoría append-only con hash encadenado. Idempotencia por clave. ADR para las decisiones.

## Criterios de aceptación
Tests unitarios de cada regla; test de integración con Testcontainers; el motor coincide con el resolutor de referencia de la fase 1 en las 60 licitaciones de ambos conjuntos (ruta crítica y duración), leyendo la verdad terreno en JSON como fixture. Un agente no puede aprobar; un analista no puede escribir.

## Notas
Las reglas duras viven aquí, no en los agentes. Documenta los casos límite.

## Al terminar
Ejecuta `make test`, actualiza PROGRESS.md (qué se hizo, decisiones, deuda conocida, siguiente fase), haz commit con Conventional Commits y para. No empieces la siguiente fase.
