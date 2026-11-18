"""Planning helpers: activity durations, dependency resolution, working-day calendar."""

from __future__ import annotations

import calendar
from datetime import date, timedelta
from decimal import ROUND_CEILING, Decimal

from .catalog import DEPENDENCY_RULES, WORK_ITEM_BY_CODE


def activity_duration(quantities: dict[str, Decimal], crews: int) -> int:
    """Working days = ceil(sum(quantity / rate) / crews), at least 1."""
    crew_days = sum(
        (q / WORK_ITEM_BY_CODE[code].rate_per_day for code, q in quantities.items()), Decimal(0)
    )
    days = int((crew_days / crews).to_integral_value(rounding=ROUND_CEILING))
    return max(days, 1)


def resolve_links(present: set[str]) -> list[tuple[str, str, int]]:
    """Dependency rules restricted to the present activities.

    A missing activity is bypassed: its predecessors link to its successors with
    the lags added (the missing activity counts as zero duration). When several
    routes join the same pair, the largest lag wins.
    """
    preds: dict[str, list[tuple[str, int]]] = {}
    for p, s, lag in DEPENDENCY_RULES:
        preds.setdefault(s, []).append((p, lag))

    def present_preds(act: str, seen: frozenset[str]) -> dict[str, int]:
        out: dict[str, int] = {}
        for p, lag in preds.get(act, []):
            if p in seen:
                raise ValueError(f"cycle in dependency rules at {p}")
            if p in present:
                out[p] = max(out.get(p, 0), lag)
            else:
                for pp, lag2 in present_preds(p, seen | {p}).items():
                    out[pp] = max(out.get(pp, 0), lag + lag2)
        return out

    links = []
    for act in sorted(present):
        for p, lag in sorted(present_preds(act, frozenset({act})).items()):
            links.append((p, act, lag))
    return links


def workday_date(start: date, index: int) -> date:
    """Date of 0-based working day `index` (Monday to Friday, no holidays).

    `start` must be a Monday."""
    if start.weekday() != 0:
        raise ValueError("start must be a Monday")
    weeks, day = divmod(index, 5)
    return start + timedelta(days=7 * weeks + day)


def add_months(d: date, months: int) -> date:
    month0 = d.month - 1 + months
    year, month = d.year + month0 // 12, month0 % 12 + 1
    day = min(d.day, calendar.monthrange(year, month)[1])
    return date(year, month, day)


def contract_deadline(start: date, months: int) -> date:
    """Last calendar day allowed by a term of `months` starting on `start`."""
    return add_months(start, months) - timedelta(days=1)


def months_needed(start: date, end: date) -> int:
    n = 1
    while contract_deadline(start, n) < end:
        n += 1
    return n
