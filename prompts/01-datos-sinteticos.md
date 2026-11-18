# Fase 1: generador de datos con verdad terreno

Lee CLAUDE.md, PLAN.md y PROGRESS.md antes de empezar. Trabaja en modo plan: primero propón la lista de ficheros, las pruebas y los riesgos, y espera mi aprobación.

## Tarea
Implementa el generador en Python (`make data SEED=42`) descrito en docs/SYNTHETIC_DATA.md: 30 licitaciones de 3 tamaños, pliego en páginas con cláusulas numeradas, anexo, memoria de mediciones con ruido de formato, tarifas propias, partes diarios y avance semanal. Incluye los casos difíciles plantados (contradicciones, partidas duplicadas, instrucciones ocultas variadas con su gemelo limpio, preguntas sin respuesta) y las variantes de redacción. Genera dos conjuntos: desarrollo (`SEED=42`) y evaluación (`SEED=1337 SPLIT=eval`, con plantillas `eval_only`). Implementa el resolutor según las definiciones operativas de docs/SYNTHETIC_DATA.md. Escribe un resolutor de referencia independiente (CPM con networkx) y guarda la verdad terreno en JSON. Renderiza los documentos a PDF y a texto con número de página.

## Criterios de aceptación
Dos ejecuciones con la misma semilla dan ficheros idénticos (PDF con metadatos fijos). Tests: cada campo esperado aparece en la página indicada; el planning de referencia es acíclico; hay instrucciones plantadas en el 10 % de las licitaciones y cada una tiene gemelo; las plantillas `eval_only` no aparecen en desarrollo. Documenta el formato en data/README.md.

## Notas
Datos totalmente ficticios. Sin nombres reales. Sin dependencias pesadas.

## Al terminar
Ejecuta `make test`, actualiza PROGRESS.md (qué se hizo, decisiones, deuda conocida, siguiente fase), haz commit con Conventional Commits y para. No empieces la siguiente fase.
