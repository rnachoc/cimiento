# Fase 7: servidor MCP de solo lectura

Lee CLAUDE.md, PLAN.md y PROGRESS.md antes de empezar. Trabaja en modo plan: primero propón la lista de ficheros, las pruebas y los riesgos, y espera mi aprobación.

## Tarea
services/mcp con el SDK oficial de Python: listar proyectos y licitaciones, leer un planning y su ruta crítica, ver una propuesta con sus citas, leer la auditoría y buscar en documentos. Siempre con rol ANALYST. Enmascara las tarifas propias (precios unitarios y rendimientos) y el texto de las instrucciones plantadas detectadas (se muestra solo su cita).

## Criterios de aceptación
Un test lee todo a través de MCP y el núcleo rechaza cualquier escritura; ejemplo de configuración en el README.

## Notas
Solo lectura. Ninguna herramienta de escritura.

## Al terminar
Ejecuta `make test`, actualiza PROGRESS.md (qué se hizo, decisiones, deuda conocida, siguiente fase), haz commit con Conventional Commits y para. No empieces la siguiente fase.
