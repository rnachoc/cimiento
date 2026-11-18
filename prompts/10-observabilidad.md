# Fase 10: observabilidad y coste

Lee CLAUDE.md, PLAN.md y PROGRESS.md antes de empezar. Trabaja en modo plan: primero propón la lista de ficheros, las pruebas y los riesgos, y espera mi aprobación.

## Tarea
OpenTelemetry con convenciones GenAI: una traza por licitación con cada agente, llamada al modelo (modelo y tokens), herramienta y petición al núcleo. Métricas y un panel de Grafana aprovisionado. `make cost-report` convierte tokens en euros solo para los modelos con precio configurado.

## Criterios de aceptación
La CI procesa las licitaciones con telemetría activada y no encuentra texto de documentos en ningún span; el informe de coste se genera.

## Notas
La base sin contenido ya existe desde la fase 4a; aquí se completa. La telemetría lleva identificadores, recuentos y tiempos, nunca texto.

## Al terminar
Ejecuta `make test`, actualiza PROGRESS.md (qué se hizo, decisiones, deuda conocida, siguiente fase), haz commit con Conventional Commits y para. No empieces la siguiente fase.
