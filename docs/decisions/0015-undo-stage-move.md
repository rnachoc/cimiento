# ADR 0015: Undoing a stage move

## Context
A tender only moved forward, so a misclick (for example from under review to decision pending) could not be taken back. Real bids need a way out for a mistake, but a bid that was submitted has left the company and cannot be pulled back by editing a row.

## Decision
1. **Where it applies.** The last move can be undone while the tender is under review, decision pending or in preparation. Submitted, awarded, lost and discarded tenders cannot be taken back; for a submitted bid a new tender is created.
2. **A new move, never a delete.** The undo goes back to the previous stage as a new `tender_stage_change` row (`revert = true`, V8) with its written reason, and is audited as `TENDER_STAGE_REVERTED` (code, from, to; the reason stays out of the chain, as with every move). An undo cannot be undone.
3. **Who.** Only the person who made the move, or management. The role rules of ADR 0013 still apply to the stage it goes back to, and a site manager never moves tenders.
4. **Pure rule.** `Pipeline.revertTarget` and `Pipeline.checkRevert` return the target and the reasons; the service gathers the facts. `POST /api/tenders/{id}/stage/revert {note}` (REVIEWER key plus a person session). The portfolio item carries `revertTo` and `lastMovedBy` so the console only shows what the core would accept.

## Consequences
- The history keeps both the mistake and its correction, with who and why.
- The console button is a convenience: the core decides.
