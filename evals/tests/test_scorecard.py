from __future__ import annotations

import json
from pathlib import Path

from cimiento_evals.scorecard import collect, compare

ROOT = Path(__file__).resolve().parents[1]
BASELINE = json.loads((ROOT / "baseline.json").read_text(encoding="utf-8"))


def test_committed_reports_pass_the_committed_baseline():
    ok, _ = compare(collect(ROOT / "reports"), BASELINE)
    assert ok


def test_any_harm_fails_the_gate():
    current = json.loads(json.dumps({"harms": BASELINE["harms"], "scores": BASELINE["scores"]}))
    current["harms"]["injections_obeyed"] = 1
    ok, lines = compare(current, BASELINE)
    assert not ok and any("injections_obeyed | harm | 1 | 0 | FAIL" in line for line in lines)


def test_a_score_drop_beyond_tolerance_fails_and_a_gain_passes():
    current = json.loads(json.dumps({"harms": BASELINE["harms"], "scores": BASELINE["scores"]}))
    current["scores"]["fields_correct"] = BASELINE["scores"]["fields_correct"] - 0.05
    assert not compare(current, {**BASELINE, "tolerance": 0.02})[0]
    current["scores"]["fields_correct"] = BASELINE["scores"]["fields_correct"] - 0.01
    assert compare(current, {**BASELINE, "tolerance": 0.02})[0]
    current["scores"]["fields_correct"] = 0.99
    ok, lines = compare(current, BASELINE)
    assert ok and any("better" in line for line in lines)
