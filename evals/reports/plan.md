# Planning eval

Generated 2026-11-18 by `make eval-plan`. Source of answers: **keyless baseline (rule-based planner)**.

Schedules are checked with the reference solver (no cycles, positive durations, known activities) and compared with the reference schedule of each tender. The reference follows the generator's productivity and dependency rules, so these numbers measure agreement with those rules, not with real sites.

| Metric | Eval | Dev |
|---|---|---|
| Schedules proposed | 30/30 | 30/30 |
| Valid schedules | 30/30 | 30/30 |
| Total duration exact | 30/30 | 30/30 |
| Mean absolute duration error | 0 days (0.0 %) | 0 days (0.0 %) |
| Critical path identical | 30/30 | 30/30 |
| Critical path overlap (mean Jaccard) | 1.0 | 1.0 |
| Contract-term flag agrees with reference | 30/30 | 30/30 |

When the contract term is not found, the tender is still registered and the core sends its schedule to a person (CONTRACT_TERM warning).
