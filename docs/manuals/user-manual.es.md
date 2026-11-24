# Cimiento: manual de usuario

Versión de la aplicación: rama `main`, noviembre de 2026. Las capturas y los ejemplos usan datos sintéticos y una empresa ficticia, Constructora Brisa.

## 1. Qué es Cimiento

Cimiento ayuda a una constructora a preparar ofertas de obra y a seguir las obras adjudicadas. Unos agentes leen los documentos de cada licitación (pliego, anexo y mediciones) y **proponen** datos, mediciones, presupuesto, planning, riesgos e informes. El núcleo de la aplicación comprueba cada propuesta con reglas en código y **una persona decide**: la aprueba, la corrige o la rechaza.

Tres ideas que conviene tener presentes:

- **La IA propone, el código decide y una persona firma.** Ningún agente escribe en la base de datos.
- **Cada dato lleva su cita.** Documento y página, con el texto exacto. Si un dato no aparece en los documentos, la aplicación lo dice en lugar de inventarlo.
- **Todo queda auditado.** Cada decisión guarda quién la tomó, cuándo y sobre qué, en un registro encadenado que no se puede reescribir.

## 2. Acceso

1. Abre la dirección que te haya dado tu empresa (por ejemplo `https://cimiento.tuempresa.es`).
2. Escribe tu **usuario** y tu **token personal**. El token te lo entrega la persona que administra la aplicación; no lo compartas.
3. La sesión dura **8 horas**. Después vuelve a pedir el acceso.
4. Para salir, pulsa **Salir** en la cabecera. En un ordenador compartido, sal siempre al terminar.

En la cabecera ves tu nombre y tu rol. Los botones de las acciones que tu rol no puede hacer aparecen desactivados con el motivo al pasar el ratón.

## 3. Roles

Cada persona tiene uno de estos tres roles. El núcleo comprueba siempre el rol, aunque la pantalla dejara pulsar un botón.

| Acción | Estudios | Jefatura de obra | Gerencia |
|---|---|---|---|
| Decidir mediciones, presupuesto, análisis del pliego y borradores de memoria | Sí | No | Sí |
| Decidir planning, avance de obra, informes y riesgos | No | Sí | Sí |
| Decidir importes iguales o superiores al umbral (1.000.000 € sin IVA por defecto) | No | No | Sí |
| Mover licitaciones entre etapas | Sí, salvo "Preparando oferta" y "Descartada" | No | Sí |
| Deshacer el último cambio de etapa | Solo si lo hizo él | No | Sí |
| Cambiar el calendario laboral de una obra | No | Sí | Sí |
| Emitir certificaciones mensuales | No | Sí | Sí |

**Cuatro ojos:** quien corrige una propuesta no puede aprobarla. La corrección queda pendiente y la aprueba otra persona con permiso.

## 4. Bandeja de revisión

Es la pantalla de inicio. Muestra las propuestas que esperan a una persona.

Al abrir una propuesta ves:

- **Los datos propuestos** (por ejemplo, el plazo de ejecución o el presupuesto).
- **Las citas**: documento, página y texto. Comprueba que el texto dice lo que la propuesta afirma.
- **Las reglas** que ha pasado o no: unidades, sumas, presupuesto, ruta crítica, plazo contractual.
- **Por qué necesita revisión**: por ejemplo, un importe sobre el umbral o un plazo que supera el del contrato.
- **Avisos de seguridad**: si un documento contenía una instrucción dirigida a la máquina ("ignora lo anterior..."), aparece un aviso. La aplicación la detecta, la informa y no la obedece.

Después decides:

- **Aprobar**: la propuesta se ejecuta y queda auditada con tu nombre.
- **Corregir**: cambias los valores. La corrección queda pendiente y la aprueba otra persona.
- **Rechazar**: la propuesta se descarta y no se ejecuta nada.

En las propuestas de planning y de presupuesto hay un botón para **exportar**: el planning en formato MS Project (XML) y el presupuesto en BC3 (FIEBDC-3). Si la propuesta aún no está aprobada, el archivo se marca como borrador.

## 5. Cartera de licitaciones

Menú **Cartera**. Cada columna es una etapa:

Por revisar, En estudio, Decisión pendiente, Preparando oferta, Presentada y Cerradas (adjudicada, perdida o descartada).

Cada tarjeta muestra el código, la recomendación de los agentes (ofertar, no ofertar o revisar), la fecha de presentación con la cuenta atrás y cuántas propuestas quedan por revisar. Las que vencen en 7 días o menos se resaltan, y las vencidas aparecen en rojo.

**Mover una licitación:** pulsa la etapa siguiente en la tarjeta. El núcleo aplica estas reglas:

- Solo se avanza por el orden de las etapas, o se descarta.
- "Preparando oferta", "Descartada" y "Perdida" piden un **motivo escrito**.
- Para decidir ofertar hace falta el **análisis del pliego aprobado**.
- Para marcar una oferta como **presentada** no puede quedar ninguna propuesta por revisar.

**Deshacer un error:** si te equivocas al mover una licitación, pulsa **↩ Volver a ...** en la tarjeta y escribe el motivo. Se puede deshacer el último cambio mientras la licitación está en estudio, en decisión pendiente o preparando oferta. Lo puede deshacer quien hizo el cambio o gerencia. Una oferta **presentada** ya no se puede deshacer: habría que crear una licitación nueva. El deshacer no borra nada: queda como un cambio más en el historial, marcado "(deshecho)".

## 6. Ficha de licitación

Pulsa el título de una licitación para abrir su ficha. Reúne en una página:

- **Datos clave con su cita**: presupuesto base, plazos, fecha de presentación, visita obligatoria, penalizaciones. Los datos que no aparecen en los documentos se listan como "no consta".
- **Presupuesto** calculado con las tarifas de la empresa (sin IVA).
- **Riesgos** detectados, por ejemplo contradicciones entre el pliego y el anexo.
- **Planning y ruta crítica** en un diagrama de barras; en verde las actividades críticas.
- **Calendario laboral** (ver el apartado 7).
- **Documentos** generados (informes y borradores).
- **Historial de etapas** con quién hizo cada cambio y su motivo.

## 7. Calendario laboral

En la ficha de cada licitación, panel **Calendario laboral**. Por defecto la obra trabaja de lunes a viernes sin festivos.

1. Elige **Festivos**: "Sin festivos" o "Nacionales de España". Los nacionales son los diez comunes a toda España, con el Viernes Santo calculado cada año.
2. Añade los **festivos locales y autonómicos** con **+ Añadir festivo**: fecha y nombre.
3. Pulsa **Guardar calendario**.

El panel muestra los festivos que caen en días laborables dentro de la obra y cuánto se retrasa el fin del planning. El cambio afecta al fin del planning, a la regla de plazo contractual, al seguimiento de obra y a la exportación a MS Project. El plazo del contrato sigue contando en meses naturales.

Solo jefatura de obra y gerencia pueden cambiar el calendario, y cada cambio queda auditado.

## 8. Seguimiento de obra

Desde la ficha de una licitación que ya tiene avance de obra, pulsa **Ver seguimiento de obra →**. Verás:

- La **curva de avance**: lo planificado frente a lo real, semana a semana.
- Las **desviaciones por actividad**: porcentaje previsto y real, y los días de retraso.
- Las **alarmas**: una actividad está en alarma cuando su retraso supera su holgura, porque entonces mueve la fecha de fin.
- El **impacto en la fecha de fin**: fin planificado frente a fin previsto. Si una actividad retrasada todavía no ha empezado, el retraso es un mínimo y la pantalla lo indica con "al menos".

El avance semanal lo proponen los agentes y lo aprueba jefatura de obra o gerencia, como cualquier otra propuesta.

## 9. Certificaciones mensuales

Desde la ficha o el seguimiento de obra, pulsa **Certificaciones →**. La certificación valora cada mes la obra ejecutada.

1. Elige el **mes**. La pantalla indica en qué semana de avance se hace el corte.
2. Revisa la **relación valorada**: cada actividad con sus partidas, el presupuesto, el porcentaje a origen, lo ejecutado a origen, lo certificado antes y lo de este periodo.
3. Revisa los **totales**: periodo, retención, líquido, IVA y total a facturar.
4. Pulsa **Emitir certificación**.

Todo el cálculo lo hace el núcleo; nadie escribe importes a mano. Para emitir, el presupuesto y el avance tienen que estar aprobados, y los meses se certifican en orden. Si algo lo impide, la pantalla dice el motivo. Una certificación emitida no se puede modificar: un error se corrige en la siguiente.

En **Certificaciones emitidas**, el botón **PDF ↓** descarga cada certificación como documento A4 para enviarlo o imprimirlo. El PDF se genera a partir de lo emitido, sin recalcular nada.

La retención (5 %) y el IVA (21 %) son parámetros que configura la persona que administra la aplicación.

## 10. Dirección

Menú **Dirección**. Resumen de toda la cartera: licitaciones por etapa, tasa de adjudicación, importe en cartera y adjudicado, tiempo medio por etapa, carga de revisión, cómo deciden las personas (aprobadas, corregidas, rechazadas) y fechas de presentación de los próximos 30 días.

## 11. Auditoría

Cada propuesta muestra su rastro de auditoría: cuándo llegó, qué reglas pasó, quién decidió y cuándo se ejecutó. El registro está encadenado por hash: si alguien alterase una fila antigua, la verificación de la cadena fallaría.

## 12. Preguntas frecuentes

**Un botón aparece desactivado.** Pasa el ratón por encima: indica el motivo (tu rol, una regla pendiente o la sesión caducada).

**La aplicación dice que un dato "no consta".** Los agentes no encontraron ese dato en los documentos con una cita válida. Búscalo en el pliego y, si existe, corrige la propuesta.

**He aprobado algo por error.** Las decisiones ejecutadas no se borran. Rechaza o corrige la siguiente propuesta del mismo tipo y, si es un cambio de etapa, usa "Volver a ...".

**La sesión ha caducado.** Vuelve a entrar con tu usuario y tu token.

**He perdido mi token.** Pide uno nuevo a la persona que administra la aplicación. El anterior deja de valer cuando lo sustituye.
