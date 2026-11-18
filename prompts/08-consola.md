# Fase 8: consola de revisión (Angular)

Lee CLAUDE.md, PLAN.md y PROGRESS.md antes de empezar. Trabaja en modo plan: primero propón la lista de ficheros, las pruebas y los riesgos, y espera mi aprobación.

## Tarea
web/console en Angular: bandeja de lo que las reglas mandaron a una persona; por propuesta, los hechos, las reglas aplicadas, las citas con su texto, las señales de riesgo y la auditoría; formulario para aprobar, corregir o rechazar. El navegador no guarda credenciales: nginx añade la clave de rol REVIEWER y aplica una Content-Security-Policy estricta.

## Criterios de aceptación
Playwright aprueba una propuesta en un navegador real en CI y el núcleo la marca como ejecutada; pruebas de componentes con Vitest.

## Notas
Interfaz sobria y clara; accesibilidad básica (teclado, contraste).

## Al terminar
Ejecuta `make test`, actualiza PROGRESS.md (qué se hizo, decisiones, deuda conocida, siguiente fase), haz commit con Conventional Commits y para. No empieces la siguiente fase.
