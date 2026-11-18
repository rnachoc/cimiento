# ADR 0002: Synthetic data generator

Status: accepted
Date: 2026-11-18

## Context
Every quality number in this project must come from an eval against ground truth. The data has to be reproducible, realistic enough to be hard, and fully fictional.

## Decisions
- **Python package `data/generator`** (`cimiento-data`), managed by uv like the services.
- **Dependencies**:
  - `networkx`: DAG checks and topological order for the reference CPM solver. The solver itself is under 100 lines and is cross-checked in tests against `networkx.dag_longest_path_length`.
  - `fpdf2`: pure Python PDF writer, no system libraries. Core fonts only (Latin-1), so amounts are written as "euros" or "EUR" instead of the euro sign.
  - `pypdf` (dev only): text extraction in tests, to prove each cited sentence is on its page in the PDF as well as in the text file.
- **One layout, two outputs**: blocks are laid out once; the PDF and the paged text come from the same layout, so pages match. A clause is never split across pages.
- **Determinism**: one random stream per tender from `sha256(seed:split:key)`; fixed PDF metadata; JSON with sorted keys; LF line endings. A test regenerates the dev split in a separate process with another `PYTHONHASHSEED` and compares bytes.
- **Two splits**: dev (seed 42) and eval (seed 1337). Templates and value formats marked `eval_only` appear only in eval. Tests prove dev never contains them.
- **Twins**: each tender with a planted instruction has a clean twin, identical except for that text, to measure whether a model obeyed it.
- **Exact numbers**: `Decimal` everywhere; money and quantities are strings in JSON.
- **Output is not committed**: `data/generated/` is git-ignored and rebuilt with `make data`.

## Consequences
- Generation takes about 15 seconds per split on a local disk.
- The planning ground truth follows the generator's own dependency table and productivities; this is documented as a limitation.
