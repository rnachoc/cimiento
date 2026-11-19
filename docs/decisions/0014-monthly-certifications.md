# ADR 0014: Monthly certifications

## Context
The console followed a tender from reading to site progress, but not to payment. On a Spanish public works contract the contractor bills each month what the executed work is worth: a valuation of the budget lines by the percent done, minus what was already certified, with a retention. That is arithmetic over data the core already holds, so a model has no reason to write any of it.

## Decision
1. **The link already existed.** Each company rate belongs to an activity (`Rate.activity`); the weekly progress is per activity. A budget line therefore has a percent: line, then activity, then percent done. Nothing is spread or guessed.
2. **A pure function, `rules/Certification.java`.**
   - Cut: the last progress week whose last working day (`WorkCalendar`, five working days per week) falls inside the month. Per activity, the latest known percent up to that week (0 before any row).
   - Per line: to date = amount x percent / 100, 2 decimals, HALF_UP. Previous = the to-date of the last issued certification. Period = to date - previous.
   - Retention (5 % by default) is taken on the period and VAT (21 % by default) on what is left. Both are configurable and fictional.
   - Blocking rules, returned as messages like `Pipeline.check`: budget or progress not approved, no progress week closes in the month, no new progress since the last certification, month already certified, months out of order, progress that goes back (negative period) or above 100 %.
3. **Issuing is an act by a person.** `POST /api/tenders/{id}/certifications {month}` needs a signed-in site manager or manager (ADR 0013); estimating cannot. The core recalculates; the request carries no amount. The result is stored as an immutable snapshot (`certification`, with a trigger that refuses UPDATE and DELETE) and audited as `CERTIFICATION_ISSUED` with identifiers and amounts, no free text. A mistake is corrected by the next certification, not by editing one.
4. **Reading is free.** `GET /api/tenders/{id}/certification?month=` previews the calculation and says what blocks it; `GET .../certifications` lists the issued ones. The MCP server gets `get_certifications`, totals only (line amounts stay hidden like every unit price).
5. The console gets `/certificaciones/:id`: month picker, valuation by activity with its work items, totals, the reasons that block a month, an Issue button disabled with its reason, and the list of issued certifications.

## Consequences
- No new dependency. Amounts are BigDecimal end to end and the console only formats them.
- Months are certified in order, which keeps "previous" equal to the last snapshot. Changing the approved budget after the first certification can make a line go below what was certified; the core then refuses the month and says which line.
- Tests: hand-computed amounts and roundings, the blocking rules, a property (the periods of all months add up to the last to-date) and every generated site in both splits (the to-date at the last week equals an independent sum from the ground truth budget and the progress CSV, to the cent).
- Out of scope: PDF or BC3 export of a certification, price revisions and invoicing. Retention and VAT percentages are not legal advice.
