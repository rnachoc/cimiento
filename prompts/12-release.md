# Fase 12: documentación y release

Lee CLAUDE.md, PLAN.md y PROGRESS.md antes de empezar. Trabaja en modo plan: primero propón la lista de ficheros, las pruebas y los riesgos, y espera mi aprobación.

## Tarea
README en inglés: qué es, principios, arquitectura, inicio rápido (`make demo` sin clave), resultados (línea base y modelo real por separado), limitaciones conocidas, cómo se mide. docs/architecture.md con diagramas, ADRs completos, SECURITY.md revisado. Guion de un vídeo corto de demo (2 a 3 minutos) que grabo yo. Etiqueta v1.0.0.

## Criterios de aceptación
Un desconocido clona, ejecuta `make demo` sin clave y ve resultados; el README no afirma nada que no esté medido.

## Notas
Comprueba que private/ no está en el historial de git.

## Al terminar
Ejecuta `make test`, actualiza PROGRESS.md (qué se hizo, decisiones, deuda conocida, siguiente fase), haz commit con Conventional Commits y para. No empieces la siguiente fase.
