# Fase 5: agente planificador

Lee CLAUDE.md, PLAN.md y PROGRESS.md antes de empezar. Trabaja en modo plan: primero propón la lista de ficheros, las pruebas y los riesgos, y espera mi aprobación.

## Tarea
Agente que propone actividades, duraciones (usando las tarifas de rendimiento) y dependencias desde las mediciones y el pliego. El núcleo valida (sin ciclos, duraciones positivas, dependencias existentes), calcula la ruta crítica y compara con el plazo contractual. Si el planning supera el plazo, el núcleo lo marca y exige revisión.

## Criterios de aceptación
Planning válido en el 100 % de las licitaciones; error medio de duración total y coincidencia de ruta crítica frente a la referencia, medidos y publicados; test con dependencias circulares plantadas que el núcleo rechaza.

## Notas
Esfuerzo alto si se atasca. Mide en el conjunto de evaluación y anota en limitaciones que el error mide la distancia a las reglas del generador, no a obras reales. Las reglas de dependencia típicas van en una tabla, no en el prompt.

## Al terminar
Ejecuta `make test`, actualiza PROGRESS.md (qué se hizo, decisiones, deuda conocida, siguiente fase), haz commit con Conventional Commits y para. No empieces la siguiente fase.
