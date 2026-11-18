# ADR 0012: Bid pipeline and file exports

## Context
The console showed proposals one by one, but nothing tracked where each tender stands as a business decision: still to read, under study, waiting for the bid/no-bid call, being prepared, submitted, won or lost. Schedules and budgets could be approved but not taken to the tools planners and estimators already use.

## Decision
1. **Pipeline stages live in the core.** `tender.stage` with eight values (`TO_REVIEW`, `UNDER_REVIEW`, `DECISION_PENDING`, `IN_PREPARATION`, `SUBMITTED`, `AWARDED`, `LOST`, `DISCARDED`). The allowed moves and their conditions are a pure function (`rules/Pipeline.java`) with unit tests:
   - only forward edges, or discarding before submission; `AWARDED`, `LOST` and `DISCARDED` are final;
   - deciding to bid (`IN_PREPARATION`), discarding and a lost bid need a written reason;
   - deciding to bid needs an approved `BID_ANALYSIS`;
   - marking a bid as submitted needs every proposal of the tender decided.
2. **Only a REVIEWER moves a tender** (`POST /api/tenders/{id}/stage`). Each move is audited (`TENDER_STAGE_CHANGED` with from and to) and the reason is stored in `tender_stage_change`, outside the audit chain, so the event stream carries no free text.
3. **The portfolio is computed, not stored**: `GET /api/portfolio` derives the submission deadline from the latest live bid analysis (`fecha_presentacion`, flagged when not yet approved) and the recommendation from the latest bid/no-bid report. Nothing a model wrote becomes a stored fact without review.
4. **Exports are code, not model output.** `GET /api/proposals/{id}/export` returns:
   - a schedule as Microsoft Project XML (MSPDI): tasks with the core's CPM dates on the Monday to Friday calendar, finish-to-start links with lag, critical flag and total float;
   - a budget as FIEBDC-3 (BC3), the Spanish budget exchange format: a root concept, its breakdown and one priced concept per line, in the Windows-1252 code page with CR LF line ends.
   Proposals that failed the rules or were rejected cannot be exported; a pending one downloads with `-borrador` in its name.
5. The console gets a **Cartera** view (columns by stage, deadline countdown, pending reviews, moves with a reason when required) and download buttons on schedule and budget proposals. The MCP server gets a read-only `get_portfolio` tool.

## Consequences
- No new dependency: XML is built with the JDK, BC3 is plain text.
- MSPDI and BC3 are checked by unit tests against the core's own CPM and pricing; they are not yet opened in Microsoft Project or a BC3 reader in CI. Importing them back is out of scope.
- Stage moves are not undone: a wrong move is fixed by the next allowed move, and the history keeps both.
