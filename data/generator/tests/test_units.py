"""Unit tests for the small helpers."""

from __future__ import annotations

import random
from datetime import date
from decimal import Decimal

import pytest

from cimiento_data.numwords import to_words
from cimiento_data.planning import (
    activity_duration,
    add_months,
    contract_deadline,
    months_needed,
    resolve_links,
    workday_date,
)
from cimiento_data.templates import render_value
from cimiento_data.textnorm import format_es, normalize, parse_es


@pytest.mark.parametrize(
    "n,plain,apoc",
    [
        (0, "cero", "cero"),
        (1, "uno", "un"),
        (12, "doce", "doce"),
        (16, "dieciséis", "dieciséis"),
        (21, "veintiuno", "veintiún"),
        (31, "treinta y uno", "treinta y un"),
        (40, "cuarenta", "cuarenta"),
        (100, "cien", "cien"),
        (101, "ciento uno", "ciento un"),
        (999, "novecientos noventa y nueve", "novecientos noventa y nueve"),
    ],
)
def test_number_words(n, plain, apoc):
    assert to_words(n) == plain
    assert to_words(n, apocope=True) == apoc


def test_number_words_range():
    with pytest.raises(ValueError):
        to_words(1000)


@pytest.mark.parametrize(
    "value,decimals,style,expected",
    [
        ("1234567.891", 2, "dots", "1.234.567,89"),
        ("1234.5", 2, "plain", "1234,50"),
        ("1234.5", 2, "spaces", "1 234,50"),
        ("1234.50", 2, "trim", "1234,5"),
        ("12.00", 2, "trim", "12"),
        ("0.005", 2, "dots", "0,01"),
        ("999", 0, "dots", "999"),
    ],
)
def test_format_es(value, decimals, style, expected):
    assert format_es(Decimal(value), decimals, style) == expected


def test_format_parse_roundtrip():
    r = random.Random(7)
    for _ in range(2000):
        v = Decimal(r.randint(0, 10**9)) / 100
        for style in ("dots", "plain", "spaces", "trim"):
            assert parse_es(format_es(v, 2, style)) == v


@pytest.mark.parametrize("bad", ["1,234.50", "1.5", "abc", "", "1.23,4", "-3"])
def test_parse_rejects(bad):
    with pytest.raises(ValueError):
        parse_es(bad)


def test_normalize():
    assert normalize("  Plazo\n de  EJECUCIÓN­ ") == "plazo de ejecución"
    assert normalize("1.234,50 m³") == "1.234,50 m3"


def test_render_values():
    assert render_value("months.words_num", 21) == "veintiún (21) meses"
    assert render_value("months.num", 1) == "1 mes"
    assert render_value("money.dots", Decimal("1234.5")) == "1.234,50 euros"
    assert render_value("pct.words_num", 5) == "cinco por ciento (5 %)"
    assert render_value("date.long", date(2027, 3, 1)) == "1 de marzo de 2027"
    assert render_value("date.slash", date(2027, 3, 1)) == "01/03/2027"
    assert render_value("permil.trim", Decimal("0.60")) == "0,6 euros"


def test_workday_calendar():
    monday = date(2027, 1, 4)
    assert workday_date(monday, 0) == monday
    assert workday_date(monday, 4) == date(2027, 1, 8)  # Friday
    assert workday_date(monday, 5) == date(2027, 1, 11)  # next Monday
    with pytest.raises(ValueError):
        workday_date(date(2027, 1, 5), 0)


def test_months():
    assert add_months(date(2027, 1, 31), 1) == date(2027, 2, 28)
    assert contract_deadline(date(2027, 1, 4), 2) == date(2027, 3, 3)
    assert months_needed(date(2027, 1, 4), date(2027, 3, 3)) == 2
    assert months_needed(date(2027, 1, 4), date(2027, 3, 4)) == 3


def test_activity_duration_rounds_up():
    # 60 m3/day: 61 m3 with one crew -> 2 days; with two crews -> 1 day
    assert activity_duration({"MT01": Decimal(61)}, 1) == 2
    assert activity_duration({"MT01": Decimal(61)}, 2) == 1
    assert activity_duration({"MT01": Decimal("0.01")}, 3) == 1


def test_resolve_links_bypasses_missing_activities():
    small = {"A01", "A02", "A03", "A04", "A05", "A08", "A11"}
    links = set(resolve_links(small))
    # A11 follows A06 (lag 2, missing) and A10 (missing); both bypassed.
    assert ("A04", "A11", 2) in links
    assert ("A08", "A11", 0) in links
    assert all(p in small and s in small for p, s, _ in links)
    assert len({(p, s) for p, s, _ in links}) == len(links)
