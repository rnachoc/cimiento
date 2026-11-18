# ADR 0006: Planner agent and optional contract term

Status: accepted
Date: 2026-11-18

## Context
Phase 5 adds a planner that proposes activities, durations and dependencies from the measurements and the clauses. The core validates the graph, computes the critical path and compares the end date with the contract term.

## Decisions
- **Durations are computed by code**: activities come from the measured items grouped by the company rate table's activity; duration = ceil(sum(quantity / productivity) / crews), at least one day. The model never writes a duration.
- **Typical dependencies live in a table** (`planner.DEPENDENCIES`), not in a prompt. Missing activities are bypassed with their lags added. The model may only choose the number of crews and change links; whatever it proposes, the core checks the graph (cycles, unknown activities, positive durations, negative lags) and rejects invalid schedules.
- **Crew heuristic without a key**: one, two or three crews by total crew-days (thresholds 240 and 650, set on the dev split).
- **Unknown contract term no longer blocks a tender.** The planning eval showed that when the reader missed the term, the core refused to register the tender and no agent could run for it. The term is now optional in the core (migration V3). When it is unknown, the `CONTRACT_TERM` rule warns ("contract term unknown"), which sends every schedule of that tender to a person, and the stored schedule has no deadline and no verdict. This is a design change, not a change to the reading rules: field extraction numbers are unchanged.
- The planning eval uses the reference solver of the data generator to check validity and compute the critical path of each proposed schedule, independently of both the agents and the core.

## Consequences
- The reference schedules follow the generator's own productivities and dependency rules, and the planner's table encodes the same construction sequence. Agreement is therefore expected; the eval proves the plumbing (quantities, rates, crews, graph, CPM, term check) end to end, not planning skill. The README states this.
- Circular dependencies planted in a proposal are rejected by the core (unit and integration tests in the core).
