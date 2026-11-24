# ADR 0017: Work calendar with holidays

## Context
The core turned working-day indexes into dates on a Monday to Friday calendar without holidays. Real works stop on public holidays, so the end of a schedule, the contract-term check, the site forecast and the MS Project export could all be a few days early. The generated ground truth of the 66 sites is built on the plain calendar, so it must stay the default.

## Decision
1. **Optional per work.** `tender.calendar` is `NONE` (default, Monday to Friday) or `ES_NATIONAL`, plus a list of local or regional holidays with their names (V9). With `NONE` and no local holidays every date is exactly what it was, and a test checks this for 600 working days.
2. **Rules in code.** `rules/Holidays.java` lists the ten national holidays common to every region of Spain, with Good Friday from the Gregorian Easter algorithm. Regional and local days change by place and year, so a person enters them. Holidays falling on a Sunday are not moved, because that choice is regional too.
3. **Where it applies:** schedule end and the CONTRACT_TERM rule, the schedule view (computed from the current calendar, so a later change shows at once), the site view (planned and forecast end) and the MS Project export, which also lists the holidays as non-working exceptions of the project calendar. Contract terms stay in calendar months, and progress weeks and certification cuts stay calendar weeks.
4. **A person sets it.** `POST /api/tenders/{id}/calendar` needs a site manager or management (estimating does not shape the schedule; ADR 0013). It is audited as `CALENDAR_CHANGED` with the kind and the number of local holidays; the names stay out of the chain. `GET` returns the calendar, the weekday holidays inside the work and the schedule end with and without them.

## Consequences
- No new dependency. Evals and the ground truth run on the default calendar and do not change.
- The MS Project exceptions follow the MSPDI format but have not been opened in MS Project itself.
- Out of scope: half days, summer hours, weather stoppages and per-activity calendars.
