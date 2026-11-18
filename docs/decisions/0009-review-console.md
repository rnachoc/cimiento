# ADR 0009: Review console

Status: accepted
Date: 2026-11-18

## Context
A person must see what the rules sent to review (facts, rules applied, citations with their text, risk signals, audit) and approve, correct or reject. The console is also the visible face of the project, so it should be striking, while staying accessible and secure.

## Decisions
- **Angular 22** (standalone components, signals, zoneless), Vitest for component tests, Playwright for the end-to-end test.
- **Design**: dark cinematic theme, large hero with live counts, horizontal rails of cards per proposal type (planted instructions glow red), a Gantt view of schedules with the critical path from the core, rule outcomes as pills, citations as quoted blocks with document and page. Inspiration from large streaming and product sites; no logos, names or assets of any company. System fonts only.
- **Accessibility**: skip link, visible focus, keyboard-reachable cards and actions, AA contrast on text, `aria` labels on cards and charts, `prefers-reduced-motion` disables animations.
- **No credentials in the browser**: the app calls `/api` on its own origin; nginx adds the REVIEWER key and overwrites any key the browser sends (tested: a forged key gets 403).
- **Strict Content-Security-Policy**: `script-src 'self'`, `style-src 'self' 'nonce-…'`, `connect-src 'self'`, `frame-ancestors 'none'`. Angular's runtime component styles use a per-request nonce that nginx writes into `ngCspNonce`; critical CSS inlining is off because it relies on an inline event handler. The end-to-end test fails on any CSP violation in the browser console.
- **Images**: the core and the console have Dockerfiles; `docker compose --profile app` runs Postgres, core and console. `make e2e` (and a CI job) runs a real Chromium that approves a proposal and checks with the ANALYST key that the core executed it.

## Consequences
- Anyone who can reach nginx acts as REVIEWER; real deployments need authentication in front of it (out of scope, stated in the README).
