from __future__ import annotations

from decimal import Decimal

import pytest

from cimiento_agents.planner import (
    Link,
    PlanChoice,
    build_schedule,
    default_crews,
    resolve_links,
    rule_based_choice,
)
from cimiento_agents.tender_agents import Rate

RATES = {
    "MT01": Rate("MT01", "m3", Decimal("18.50"), Decimal("60"), "A01"),
    "CI02": Rate("CI02", "m3", Decimal("160"), Decimal("15"), "A02"),
    "ES02": Rate("ES02", "m2", Decimal("65"), Decimal("40"), "A03"),
}
ITEMS = [
    {"code": "MT01", "quantity": "121"},
    {"code": "CI02", "quantity": "30"},
    {"code": "ES02", "quantity": "400"},
]


def test_durations_are_computed_by_code_and_round_up():
    s = build_schedule(ITEMS, RATES, PlanChoice(crews=1, links=[]))
    assert {a["code"]: a["duration"] for a in s["activities"]} == {"A01": 3, "A02": 2, "A03": 10}
    s2 = build_schedule(ITEMS, RATES, PlanChoice(crews=2, links=[]))
    assert {a["code"]: a["duration"] for a in s2["activities"]} == {"A01": 2, "A02": 1, "A03": 5}


def test_crews_must_be_in_range():
    with pytest.raises(ValueError):
        build_schedule(ITEMS, RATES, PlanChoice(crews=0, links=[]))


def test_default_crews_by_job_size():
    assert default_crews(Decimal(100)) == 1
    assert default_crews(Decimal(400)) == 2
    assert default_crews(Decimal(900)) == 3


def test_missing_activities_are_bypassed_in_the_rule_table():
    links = set(resolve_links({"A01", "A03"}))  # A02 missing: A01 -> A03 with A02's 3-day lag
    assert links == {("A01", "A03", 3)}


def test_rule_based_choice_uses_the_table():
    c = rule_based_choice(ITEMS, RATES)
    assert c.crews == 1
    assert [(lk.from_, lk.to, lk.lag) for lk in c.links] == [("A01", "A02", 0), ("A02", "A03", 3)]


def test_model_links_pass_through_unchanged_for_the_core_to_judge():
    # a cyclic choice is not repaired here: the core rejects it (SCHEDULE_GRAPH)
    cyclic = PlanChoice(
        crews=1, links=[Link(from_="A01", to="A02", lag=0), Link(from_="A02", to="A01", lag=0)]
    )
    s = build_schedule(ITEMS, RATES, cyclic)
    assert {(x["from"], x["to"]) for x in s["links"]} == {("A01", "A02"), ("A02", "A01")}
