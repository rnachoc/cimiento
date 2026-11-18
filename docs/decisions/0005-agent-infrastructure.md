# ADR 0005: Agent infrastructure and the bid analyst

Status: accepted
Date: 2026-11-18

## Context
Phase 4a needs the pieces every agent shares: a model layer that works with and without an API key, a coordinator, a restricted client for the core, telemetry without content, citation checks and defence against instructions planted in documents. The first agent on top is the bid analyst.

## Decisions

### Model layer with labelled sources
`llm.py` has one interface and three sources, and every result carries its source:
- `claude`: the official Anthropic SDK, `messages.create` with `output_config.format` (JSON schema generated from the Pydantic model, every object closed with `additionalProperties: false`). The stable system prompt is cached (`cache_control`); the tender text goes in the user message. Default model `claude-opus-5-5`, effort `medium`, set explicitly. A `refusal` stop reason is an error, never an answer.
- `recorded`: with `CIMIENTO_RECORD=true`, Claude answers are saved under `evals/recordings/<task>/<model>/<request hash>.json` with model and date; keyless runs replay them and label them `model@date`.
- `rule-based`: a deterministic reader written in code. It is the keyless baseline and is always reported as such.

Server-side model fallbacks are not enabled: in an eval that compares models, an answer silently served by another model would corrupt the measurement. A refusal is counted as a failure of the model that refused.

### Code checks what the model says
- Every extracted value needs a citation (document, page, literal text). The code re-reads the PDF page and drops values whose citation is not there. The eval counts dropped values.
- A rule-based injection detector runs on the documents for every tender, whatever the model. Its patterns were written from the dev split and general knowledge of prompt injection; the eval split holds instruction wordings never used to write them. Reports from the model are merged in only if their citation verifies.
- A detected instruction adds the `INJECTION_DETECTED` signal, which makes the core send the proposal to a person.

### Restricted core client
The agents' client applies an allow list before any network call: register tenders, submit proposals, read. Decisions, project creation and anything else raise `ForbiddenCall`. The core enforces the same policy on its side. Proposals carry an idempotency key derived from their content, so a retry never creates a second proposal.

### Telemetry without content
`telemetry.event` accepts only identifiers, counts, timings and flags; a string with spaces or longer than 64 characters is rejected with an error. Phase 10 builds OpenTelemetry on top of this.

### Ground truth stays outside the agents
Only the `evals` package reads ground truth. A test fails if any agent module mentions ground-truth files, and runs the coordinator with `open` patched to fail on them.

### Dependencies added
- `anthropic`: official SDK for Claude.
- `httpx`: client for the core API (already a dependency of the SDK).

### Phase 4b: the other tender agents
- **Takeoff**: the reader only returns rows as written (code, unit, quantity, duplicate flag, citation). Code parses Spanish numbers, converts units to the company's canonical unit and adds up; the core checks the sums again.
- **Cost estimator**: lines are quantity per company rate; the core computes the budget and rejects a claimed total that differs.
- **Risk analyst**: contradiction between clauses and annex (both cited), demanding clauses against the company profile (penalty, subcontracting limit), planted instructions. Each signal carries citations that were verified on the page.
- **Bid/no-bid**: explicit rules in code against a fictional company profile (turnover, similar works, minimum margin). Any contradiction or planted instruction gives REVIEW. A model may only write the narrative.
- **Proposal writer**: a template; every section names its source field (cited) or is a fixed company statement. Always a draft.
- **Q&A**: search, then an answer with a verified citation, or "No consta en los documentos." An answer whose citation does not verify is replaced by "no consta".
- When the reader misses the contract term, the tender is still registered without it (see ADR 0006); the core then sends its schedule to a person.

## Consequences
- Keyless numbers measure the rule-based reader, not a model. They are a floor for comparison and are labelled "keyless baseline".
- The rule-based reader was written against dev wording, so it scores perfectly on dev by construction; only eval numbers mean anything.
