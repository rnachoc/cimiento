# data

Synthetic tenders for a fictional contractor, Constructora Brisa, with ground truth. Nothing here is real.

```bash
make data                 # both splits: dev (seed 42) and eval (seed 1337)
make data SPLIT=eval      # one split with its default seed
make data SEED=7          # dev split with another seed
```

Output goes to `data/generated/<split>/` (git-ignored, regenerated on demand). The generator lives in `data/generator` and is tested with `make test`. The rules behind the data (definitions of critical path, citation, alarm and so on) are in [docs/SYNTHETIC_DATA.md](../docs/SYNTHETIC_DATA.md).

## Splits
- `dev`: used to build and debug agents and rules.
- `eval`: used only to report numbers. It also uses held-out wording (templates marked `eval_only`) that never appears in `dev`.

Each split has 30 tenders (10 small, 10 medium, 10 large) plus a clean twin for each tender with a planted instruction.

## Layout

```
company/tarifas.json                 company rates: unit price and productivity per work item
tenders/D-001/pliego.pdf|txt         administrative clauses
tenders/D-001/anexo.pdf|txt          technical annex (states the planned term again)
tenders/D-001/mediciones.pdf|txt     measurement table with format noise
tenders/D-001/obra/avance_semanal.csv  weekly progress per activity up to the status week
tenders/D-001/obra/partes/<date>.txt   daily site reports for the last 10 working days
tenders/D-011-gemelo/...             same tender without the planted instruction
ground_truth/D-001.json              expected answers for the tender
manifest.json                        seed, split, generator version and sha256 of every file
```

### Text files
The PDF and the `.txt` come from the same layout. Each page in the `.txt` starts with a marker:

```
=== página 2 ===
```

PDFs carry fixed metadata (creation date 2026-01-01, producer `cimiento-data`), so the same seed gives byte-identical files.

### Measurement rows
`Código | Descripción | Ubicación | Ud | Cantidad`. Numbers use Spanish format (`1.234,50`, `1234,5`, and in eval also `1 234,50`). Units vary in spelling (`m3`, `m³`, `M3`, `ml`, `uds`...) and some rows use a larger unit (`km` for pipes, `ha` for paving). Some tenders repeat a row by mistake.

### Weekly progress CSV
Separator `;`. Columns: `semana;fecha_corte;actividad;nombre;avance_planificado_pct;avance_real_pct`.

## Ground truth (`ground_truth/<id>.json`)

| Key | Content |
|---|---|
| `campos` | Each expected field with `valor`, `documento`, `pagina`, `clausula`, `texto` (literal sentence), `plantilla` and `formato`. Includes `anexo_plazo_ejecucion_meses`. |
| `mediciones` | Per work item: canonical `unidad`, exact `cantidad`, and every row with page, text, canonical quantity and `duplicada`. |
| `partidas_duplicadas`, `unidades_mezcladas` | Codes affected. |
| `presupuesto` | Lines (quantity x company price, rounded to cents) and total, VAT excluded. |
| `planning` | Activities with duration, ES, EF, LS, LF, total float and critical flag; finish-to-start links with lag; duration in working days; critical activities; contract term and deadline; `supera_plazo`. |
| `contradicciones` | Term in the clauses versus term in the annex, both cited. |
| `inyecciones` | Planted instruction: template, language, target (what it tries to change), document, page, placement, text. |
| `preguntas_sin_respuesta` | Questions whose answer is in no document. |
| `obra` | Status week, planted delays, per-activity deviations (planned and real %, SPI, delay, float, alarm), impact on the project end, daily reports with incidents. |
| `gemelo`, `gemelo_de` | Link between a tender with an instruction and its clean twin. |

Money and quantities are strings with a decimal point (`"1234.50"`) to keep them exact. Days are 0-based working-day indices from `planning.fecha_inicio` (Monday to Friday, no holidays).

## Known limits
- Documents come from templates. The held-out wording reduces overfitting but does not remove it.
- The planning rules (durations from productivity, dependency table) are the generator's own. A planner that matches them is measured against these rules, not against real sites.
- Delays are modelled as late starts at the planned pace.
