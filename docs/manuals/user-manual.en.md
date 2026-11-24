# Cimiento: user manual

Application version: `main` branch, November 2026. Screens and examples use synthetic data and a fictional contractor, Constructora Brisa. The console itself is in Spanish; button names are quoted as they appear on screen, with a translation.

## 1. What Cimiento is

Cimiento helps a construction company prepare bids and follow the works it wins. Agents read the documents of each tender (specification, annex and bill of quantities) and **propose** facts, quantities, budget, schedule, risks and reports. The core of the application checks every proposal with rules written in code, and **a person decides**: approve, correct or reject.

Three ideas to keep in mind:

- **The AI proposes, the code checks and a person signs.** No agent writes to the database.
- **Every fact carries its citation.** Document and page, with the exact text. When a fact is not in the documents, the application says so instead of making it up.
- **Everything is audited.** Each decision records who made it, when and on what, in a hash-chained log that cannot be rewritten.

## 2. Signing in

1. Open the address your company gave you (for example `https://cimiento.yourcompany.com`).
2. Type your **username** and your **personal token**. The person who runs the application gives you the token; do not share it.
3. A session lasts **8 hours**. After that, sign in again.
4. To leave, press **Salir** (sign out) in the header. On a shared computer, always sign out when you finish.

The header shows your name and role. Buttons for actions your role cannot take are disabled, and hovering over them shows why.

## 3. Roles

Each person has one of three roles. The core always checks the role, even if a screen let you press a button.

| Action | Estimating (Estudios) | Site manager (Jefatura de obra) | Management (Gerencia) |
|---|---|---|---|
| Decide quantities, budget, bid analysis and draft documents | Yes | No | Yes |
| Decide schedule, site progress, reports and risks | No | Yes | Yes |
| Decide amounts at or above the threshold (EUR 1,000,000 excluding VAT by default) | No | No | Yes |
| Upload new tenders | Yes | No | Yes |
| Move tenders between stages | Yes, except "Preparando oferta" (preparing the bid) and "Descartada" (discarded) | No | Yes |
| Undo the last stage move | Only their own | No | Yes |
| Change the work calendar of a work | No | Yes | Yes |
| Issue monthly certifications | No | Yes | Yes |

**Four eyes:** whoever corrects a proposal cannot approve it. The correction stays pending until another person with authority approves it.

## 4. Review inbox

This is the start page. It lists the proposals waiting for a person.

When you open a proposal you see:

- **The proposed values** (for example the execution term or the budget).
- **The citations**: document, page and text. Check that the text says what the proposal claims.
- **The rules** it passed or failed: units, sums, budget, critical path, contract term.
- **Why it needs review**: for example an amount over the threshold, or a schedule longer than the contract term.
- **Security warnings**: if a document contained an instruction aimed at the machine ("ignore the above..."), a warning appears. The application detects it, reports it and does not obey it.

Then you decide:

- **Aprobar** (approve): the proposal is executed and audited with your name.
- **Corregir** (correct): you change the values. The correction stays pending until another person approves it.
- **Rechazar** (reject): the proposal is discarded and nothing is executed.

Schedule and budget proposals have an **export** button: the schedule as MS Project XML and the budget as BC3 (FIEBDC-3), the Spanish exchange format for budgets. If the proposal is not approved yet, the file is marked as a draft.

## 5. Bid pipeline

Menu **Cartera**. Each column is a stage:

Por revisar (to review), En estudio (under review), Decisión pendiente (decision pending), Preparando oferta (preparing the bid), Presentada (submitted) and Cerradas (closed: awarded, lost or discarded).

Each card shows the code, the agents' recommendation (bid, no bid or review), the submission date with a countdown and how many proposals still wait for review. Tenders due in 7 days or less are highlighted, and overdue ones turn red.

**Moving a tender:** press the next stage on the card. The core applies these rules:

- Tenders only move forward through the stages, or are discarded.
- "Preparando oferta", "Descartada" and "Perdida" (lost) need a **written reason**.
- Deciding to bid needs an **approved bid analysis**.
- A bid can only be marked as **submitted** when no proposal of the tender waits for review.

**Undoing a mistake:** if you move a tender by mistake, press **↩ Volver a ...** (back to ...) on the card and write the reason. The last move can be undone while the tender is under review, decision pending or preparing the bid. Whoever made the move, or management, can undo it. A **submitted** bid cannot be taken back: create a new tender instead. Undoing deletes nothing: it is one more move in the history, marked "(deshecho)" (undone).

## 5a. Uploading a new tender

In **Cartera**, press **+ Nueva licitación** (new tender).

1. Type the **code** (for example `LIC-2027-014`; letters, digits, dot, dash and underscore), the **title** and the **planned start** of the work, which must be a Monday.
2. Attach the PDF documents: the **pliego** (specification, required), the **anexo** (annex) and the **mediciones** (bill of quantities). Each file can be up to 20 MB.
3. Press **Subir y analizar** (upload and analyse).

The list **Últimas subidas** (recent uploads) shows the status: "En cola" (queued), "Analizando…" (analysing), "Lista para revisar" (ready for review) or "Error". It refreshes by itself. When it is ready, **Ver ficha →** opens the tender and **Revisar** goes to the inbox, where its proposals wait: facts with citations, quantities, budget, schedule, risks and recommendation.

The documents are stored as uploaded and cannot be changed. If the core refuses the upload (for example, a code that already exists), the screen says why. Only estimating and management can upload tenders.

## 6. Tender file

Press the title of a tender to open its file. It gathers on one page:

- **Key facts with their citation**: base budget, terms, submission date, mandatory site visit, penalties. Facts that are not in the documents are listed as "no consta" (not stated).
- **Budget** calculated with the company's rates (VAT excluded).
- **Risks** found, for example contradictions between the specification and the annex.
- **Schedule and critical path** as a bar chart, critical activities in green.
- **Work calendar** (see section 7).
- **Documents** produced (reports and drafts).
- **Stage history** with who made each move and why.

## 7. Work calendar

In each tender file, panel **Calendario laboral**. By default a work runs Monday to Friday without holidays.

1. Choose **Festivos** (holidays): "Sin festivos" (none) or "Nacionales de España" (national holidays of Spain). These are the ten holidays common to all of Spain, with Good Friday calculated for each year.
2. Add **local and regional holidays** with **+ Añadir festivo**: date and name.
3. Press **Guardar calendario** (save).

The panel lists the holidays that fall on working days inside the work and how far they push the end of the schedule. The calendar changes the schedule end, the contract-term rule, the site forecast and the MS Project export. The contract term still counts in calendar months.

Only site managers and management can change the calendar, and every change is audited.

## 8. Site progress

From the file of a tender that already has site progress, press **Ver seguimiento de obra →** (see site progress). You see:

- The **progress curve**: planned against actual, week by week.
- **Deviations per activity**: planned and actual percent, and days of delay.
- **Alarms**: an activity is in alarm when its delay is larger than its float, because then it moves the end date.
- **Impact on the end date**: planned end against forecast end. If a delayed activity has not started yet, its delay is a lower bound and the screen says "al menos" (at least).

The weekly progress is proposed by the agents and approved by a site manager or management, like any other proposal.

## 9. Monthly certifications

From the tender file or the site view, press **Certificaciones →**. A certification values each month the work done.

1. Pick the **month**. The screen shows which progress week is used as the cut.
2. Check the **valuation**: each activity with its work items, the budget, the percent to date, the amount to date, what was certified before and this period.
3. Check the **totals**: period, retention, net, VAT and total to bill.
4. Press **Emitir certificación** (issue).

The core does all the arithmetic; nobody types an amount. To issue, the budget and the progress must be approved, and months are certified in order. If something blocks a month, the screen says why. An issued certification cannot be changed: a mistake is corrected in the next one.

Under **Certificaciones emitidas** (issued certifications), the **PDF ↓** button downloads each certification as an A4 document to send or print. The PDF is drawn from what was issued, without recalculating anything.

Retention (5 %) and VAT (21 %) are parameters set by the person who runs the application.

## 10. Management view

Menu **Dirección**. A summary of the whole pipeline: tenders by stage, win rate, amount in the pipeline and awarded, mean time per stage, review load, how people decide (approved, corrected, rejected) and submission dates in the next 30 days.

## 11. Audit

Each proposal shows its audit trail: when it arrived, which rules it passed, who decided and when it was executed. The log is hash-chained: if someone altered an old row, the chain check would fail.

## 12. Frequently asked questions

**A button is disabled.** Hover over it: it tells you why (your role, a pending rule or an expired session).

**The application says a fact is "no consta".** The agents did not find that fact in the documents with a valid citation. Look for it in the specification and, if it is there, correct the proposal.

**I approved something by mistake.** Executed decisions are not deleted. Reject or correct the next proposal of the same type and, for a stage move, use "Volver a ...".

**My session expired.** Sign in again with your username and token.

**I lost my token.** Ask the person who runs the application for a new one. The old one stops working once it is replaced.
