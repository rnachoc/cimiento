# ADR 0007: Site progress and reports

Status: accepted
Date: 2026-11-18

## Context
Phase 6 compares weekly progress and daily reports with the schedule, flags deviations and their impact on the critical path, and drafts the daily report, RFIs, the weekly report and the minutes.

## Decisions
- **Code computes every number**: SPI (actual % / planned %), delay, float, alarm and impact. The delay model matches the data: an activity that started late runs at its planned pace, so delay = (elapsed days since early start) - (actual % x duration). For an activity that should have started but shows 0 %, only a lower bound is known (elapsed days since early start); reports say "al menos".
- **Alarm** = delay greater than total float. **Impact** on the project end = CPM again with each delayed activity lengthened by its delay.
- **A small CPM pass in the agents** is needed to know float and impact before anything reaches the core. It follows the core's definitions and is tested; the core stays the authority for executed schedules.
- **Every report sentence names its source records** (weekly row, daily report, document page, missing field). `check_sources` raises if a sentence has no source or cites an unknown record, so no report with an unsupported claim is sent. Reports are drafts (`status: draft`) and go to a person under the core's routing table.
- **RFIs** come from fields the bid analyst did not find and from contradictions between documents.
- The progress update itself is a `PROGRESS_UPDATE` proposal: the core records it automatically when every activity is on plan and sends it to a person when any is behind.

## Consequences
- When a delayed activity has not started at the cut, its true delay is not in the data; the impact estimate can then be short. The eval reports these cases separately.
