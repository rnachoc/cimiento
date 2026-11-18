# Fase 11: evals completos y ejecución con modelo real

Lee CLAUDE.md, PLAN.md y PROGRESS.md antes de empezar. Trabaja en modo plan: primero propón la lista de ficheros, las pruebas y los riesgos, y espera mi aprobación.

## Tarea
Une todos los evals en `make scorecard` con comparación contra evals/baseline.json comprometido: los daños (instrucciones obedecidas, escrituras indebidas, citas falsas) deben ser 0 y las puntuaciones solo pueden variar dentro de una tolerancia. Ejecuta una vez con ANTHROPIC_API_KEY (pon antes un límite de gasto en la consola) en 2 o 3 modelos, una ejecución cada uno, sobre el conjunto de evaluación, y publica la tabla con la fecha, los modelos y recuentos. Antes, estima el coste y enséñamelo. Usa la Batch API y caché de prompts. Graba las respuestas (`CIMIENTO_RECORD=true`) para que `make demo` las reproduzca etiquetadas con modelo y fecha.

## Criterios de aceptación
Tabla de resultados con modelo real en el README, separada de la línea base; la puerta de CI falla si un daño sube de cero.

## Notas
Esfuerzo alto si se atasca. Cuenta honestamente lo que falla y por qué.

## Al terminar
Ejecuta `make test`, actualiza PROGRESS.md (qué se hizo, decisiones, deuda conocida, siguiente fase), haz commit con Conventional Commits y para. No empieces la siguiente fase.
