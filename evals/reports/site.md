# Site progress eval

Generated 2026-11-18 by `make eval-site`. Source: **keyless baseline (rule-based)**. Deviations, SPI, delays, alarms and impact are computed by code from the weekly progress file and the schedule.

| Metric | Eval | Dev |
|---|---|---|
| Planted delays detected | 42/42 | 40/40 |
| Activities wrongly flagged as delayed | 0 | 0 |
| Alarm (delay beyond float) agrees with reference | 182/184 | 185/185 |
| Delay days exact (activity already started) | 32/32 | 34/34 |
| Delays known only as a lower bound (activity not started) | 10 | 6 |
| Impact on the project end exact | 26/30 | 28/30 |
| Reports drafted (all sentences with existing sources) | 90 | 90 |
| RFIs drafted from gaps and contradictions | 30 | 6 |

Every report passes `check_sources` before it is sent to the core; a report with an unsupported claim raises an error instead, so the count of unsupported claims is 0 by construction and the run would fail otherwise.
