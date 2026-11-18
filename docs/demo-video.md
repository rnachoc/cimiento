# Demo video script (2 to 3 minutes)

Recorded by the author. Keyless: no API key is needed, so nothing is billed. Prepare before recording: run `make demo` once so the images are built, then `make demo-down` and run `make demo` again on camera (the second run takes about a minute).

| Time | Screen | What to say |
|---|---|---|
| 0:00 | README, top | Cimiento is a multi-agent system for construction tenders, scheduling and site progress. The rule: the model proposes, the code decides. All data is synthetic. |
| 0:15 | Terminal: `make demo` | One command, no API key. It generates the tenders, starts Postgres, the Java core and the review console, and runs the agents on five tenders. |
| 0:40 | Terminal: agent summary lines | Every field comes with a citation that code checked on its page. E-012 hides an instruction in its documents: it is reported, not obeyed, and its proposals go to a person. E-002 hides another one that the keyless reader misses; the eval counts that miss (2 of 3 detected) and checks it was not obeyed. |
| 1:00 | Console: inbox | Nothing the agents propose is executed until the core's rules pass and, for budgets, schedules and bid analyses, a person approves. |
| 1:15 | Console: a SCHEDULE proposal | The Gantt and the critical path are computed by the core, not by the model. Each rule outcome is listed in a fixed order. |
| 1:35 | Console: a BUDGET or BID_ANALYSIS proposal, citations | Citations show document, page and the quoted text. Approve one proposal. |
| 1:50 | Console: audit trail | Every step is in an append-only audit log, hash-chained; the demo's last line checks the chain. |
| 2:05 | README, results tables | Every number is measured against ground truth on a held-out split. Without a key these are a keyless baseline; Claude runs are reported separately. |
| 2:25 | README, Known limitations | What it does not do yet, said plainly. |
| 2:40 | Terminal: `make demo-down` | One command stops everything and removes the demo data. |

Do not show `.env`, `.demo-keys` or any API key on screen.
