# ADR 0013: People, roles and segregation of duties

## Context
Until phase 14 the console reached the core with one REVIEWER key, added by nginx. Every decision and every stage move was recorded as REVIEWER: the audit chain could prove that something was decided, not who decided it. Anyone who could open the console could approve anything, including amounts over the review threshold, and a person could rewrite an agent's proposal and execute it in the same click.

## Decision
1. **Machines keep role keys; people sign in.** AGENT and ANALYST keep their keys. nginx still adds the REVIEWER key, which now means "the console". Decisions and stage moves also need a signed-in person; without one the core answers 401.
2. **Personal tokens, not passwords.** A `person` row holds username, display name, role (`ESTIMATING`, `SITE_MANAGER`, `MANAGEMENT`) and the SHA-256 of a random personal token. People are loaded from `CIMIENTO_PEOPLE` (JSON with hashes only). `make demo` generates three fictional people and keeps their tokens in the git-ignored `.demo-keys`.
3. **Session in an HttpOnly cookie.** `POST /api/session` checks the token in constant time and sets `cimiento_session` (HttpOnly, SameSite=Strict, Path=/api, 8 hours, Secure when the request came over HTTPS). Only the SHA-256 of the session id is stored. Browser code never sees a token or the session id; it asks `GET /api/session/me` who is signed in. Sign-in and sign-out are audited.
4. **Authority is a pure function** (`rules/Authority.java`, unit tested):
   - estimating decides takeoffs, budgets, bid analyses and document drafts; the site manager decides schedules, reports, progress updates and risk signals; management decides everything;
   - a proposal routed with `AMOUNT_OVER_THRESHOLD` is decided by management only;
   - moving a tender to `IN_PREPARATION` (decide to bid) or `DISCARDED` is for management; site managers do not move tenders.
5. **Four eyes on corrections.** `CORRECT` no longer executes. The rules run on the corrected payload, the correction is stored on the proposal (`corrected_payload`, `corrected_by`) and the proposal stays `PENDING_REVIEW`. A different authorised person approves it (it then runs) or rejects it. The author of the correction gets 409 `SEGREGATION_OF_DUTIES`. No new status was added, so pending counts, the pipeline and the dashboard stay as they were.
6. **The audit chain names the person.** `audit_entry.actor_person` is new. The hash covers `ROLE/username` when there is a person and the role alone when not, so every row written before this phase verifies exactly as before. `decision.person` and `tender_stage_change.person` keep the same name next to the free text that stays outside the chain.

## Consequences
- No new dependency: hashing and random ids come from the JDK. No identity provider: OIDC or SAML would be the next step for a real company, and is out of scope for a local demo.
- Tokens are long random secrets, not something a person remembers. Losing one means issuing a new hash in `CIMIENTO_PEOPLE`. There is no self-service reset and no lockout after failed attempts.
- The console mirrors the authority rules only to disable buttons and explain why. The core decides; a test checks that the core refuses what the console hides.
- The demo is served over plain HTTP on localhost, so the session cookie is not `Secure` there. Behind HTTPS it is.
