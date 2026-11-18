# Fase 6: seguimiento de obra e informes

Lee CLAUDE.md, PLAN.md y PROGRESS.md antes de empezar. Trabaja en modo plan: primero propón la lista de ficheros, las pruebas y los riesgos, y espera mi aprobación.

## Tarea
Agente de avance: lee avance semanal y partes diarios, calcula desviaciones contra el planning (el código calcula SPI y retraso), marca el impacto en la ruta crítica. Agente de informes: parte diario, RFI, informe semanal y acta; cada frase referencia un registro de origen y el código comprueba que existe. Los RFI nacen de huecos y contradicciones detectados.

## Criterios de aceptación
Las desviaciones plantadas se detectan (medida frente a la verdad terreno); ningún informe contiene una afirmación sin fuente; tests de formato de cada informe.

## Notas
Los informes son borradores. Marca el estado en la consola.

## Al terminar
Ejecuta `make test`, actualiza PROGRESS.md (qué se hizo, decisiones, deuda conocida, siguiente fase), haz commit con Conventional Commits y para. No empieces la siguiente fase.
