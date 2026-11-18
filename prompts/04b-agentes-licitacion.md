# Fase 4b: resto de agentes de licitación

Lee CLAUDE.md, PLAN.md y PROGRESS.md antes de empezar. Trabaja en modo plan: primero propón la lista de ficheros, las pruebas y los riesgos, y espera mi aprobación.

## Tarea
Sobre la infraestructura de la fase 4a: takeoff analyst (el código suma y normaliza unidades), cost estimator (el modelo asigna tarifa; el núcleo calcula el presupuesto), risk analyst (contradicciones, plazos imposibles, cláusulas abusivas; cada señal con evidencia citada que el código vuelve a comprobar), proposal writer (borrador de memoria técnica), Q&A (cita o "no consta") e informe bid/no-bid (recomendación por reglas explícitas en código; el modelo solo redacta). Las propuestas se envían al núcleo, que valida y enruta.

## Criterios de aceptación
`make eval-bid` completo en el conjunto de evaluación: extracción de campos, mediciones exactas, error de presupuesto, contradicciones halladas, "no consta" correcto, citas verificadas, 0 instrucciones obedecidas, 0 escrituras indebidas. Ningún agente puede saltarse la revisión humana (test).

## Notas
Etiqueta siempre qué resultados son de línea base y cuáles de modelo real. Sin números inventados.

## Al terminar
Ejecuta `make test`, actualiza PROGRESS.md (qué se hizo, decisiones, deuda conocida, siguiente fase), haz commit con Conventional Commits y para. No empieces la siguiente fase.
