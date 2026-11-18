# Bid analysis eval

Generated 2026-11-18 by `make eval-bid`. Source of answers: **keyless baseline (rule-based reader)**.

Eval split (seed 1337, held-out wording). Synthetic tenders; small sample, so counts are given with every rate.

| Metric | Eval | Dev |
|---|---|---|
| Fields extracted correctly | 68.2 % (266/390) | 100.0 % (390/390) |
| Citations verified on their page | 100.0 % (1506/1506) | 100.0 % (2201/2201) |
| Planted instructions detected | 2/3 | 3/3 |
| Planted instructions obeyed (twin comparison) | 0 | 0 |
| Clean tenders with a false injection report | 0/30 | 0/30 |
| Forbidden writes (calls outside the allow list) | 0 | 0 |
| Measured items with the exact quantity | 87.4 % (332/380) | 100.0 % (380/380) |
| Budgets equal to the expected total | 26/30 | 30/30 |
| Budget error, mean / max (% of expected) | 0.0 / 0.0 | 0.0 / 0.0 |
| Planted contradictions found | 3/6 | 6/6 |
| Tenders with a false contradiction | 0 | 0 |

Bid/no-bid recommendations on eval (rules in code, no ground truth for this): {'BID': 11, 'NO_BID': 11, 'REVIEW': 4, 'none': 4}. Errors: none.

Routing is decided by the core, not measured here: every proposal of these types goes to a person by the core's routing table (tested in the core).

## Eval split, per field

| Field | Correct |
|---|---|
| anexo_plazo_ejecucion_meses | 15/30 |
| criterio_mejoras_pct | 16/30 |
| criterio_plazo_pct | 19/30 |
| criterio_precio_pct | 17/30 |
| fecha_presentacion | 23/30 |
| garantia_definitiva_pct | 19/30 |
| penalidad_demora_eur_por_mil_dia | 19/30 |
| plazo_ejecucion_meses | 26/30 |
| presupuesto_base_eur | 27/30 |
| solvencia_economica_eur | 20/30 |
| solvencia_tecnica_obras | 12/30 |
| subcontratacion_max_pct | 25/30 |
| visita_obligatoria | 28/30 |

Fields count as correct only with a citation that the code verified on the page; unverified values are dropped (0 on eval).
