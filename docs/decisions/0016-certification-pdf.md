# ADR 0016: Certification as a PDF

## Context
An issued certification lived only in the console. A contractor sends the monthly certification to the client as a document, so the console needs a file a person can download, attach and print.

## Decision
1. **Drawn from the snapshot, never recalculated.** `GET /api/tenders/{id}/certifications/{number}/pdf` reads the immutable row stored at issue time (ADR 0014): its lines, totals, cut week, who issued it and when. The renderer only formats; if the rules change later, an old PDF still prints what was issued.
2. **Dependency: Apache PDFBox 3.0.3** (Apache License 2.0). It is the usual Java library for writing PDFs, has no native parts and needs no template engine. Rendering HTML to PDF would add a browser or a heavier stack to the core. The standard Helvetica font covers Spanish text and the euro sign (WinAnsi); any other character prints as "?" instead of failing.
3. **Layout:** A4, the fictional contractor, number and month, the work, the cut week, who issued it, the valuation grouped by activity with each line, and the totals (period, retention, net, VAT and total to bill). It pages when there are many lines and says on every page that the data are synthetic and that retention and VAT are fictional parameters.
4. The console shows a "PDF" link next to each issued certification. Reading needs no person session, like every other GET.

## Consequences
- One new dependency in the core, recorded here.
- No signature: the PDF is a copy of what was issued and audited, not a signed document. A qualified electronic signature is out of scope.
- Tests: amounts and months in Spanish, the text extracted from the PDF (grouping, totals, percentages), several pages with the totals last, unsupported characters, and an integration test (type, file name, 404).
