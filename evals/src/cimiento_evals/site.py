"""Site progress eval: planted delays and alarms against the ground truth, and reports
checked for unsupported claims."""

from __future__ import annotations

import json
import time
from pathlib import Path

from cimiento_agents.batch import ModelFailed
from cimiento_agents.coordinator import analyse_tender
from cimiento_agents.core_client import RecordingCore
from cimiento_agents.llm import Model
from cimiento_agents.tender_agents import load_rates


def run(split_dir: Path, model: Model) -> dict:
    rates = load_rates(split_dir / "company" / "tarifas.json")
    core = RecordingCore()
    planted = detected = false_flags = 0
    alarm_total = alarm_ok = 0
    exact_total = exact_ok = lower_bounds = 0
    impact_ok = impact_total = 0
    reports = rfis = 0
    for gt_path in sorted((split_dir / "ground_truth").glob("*.json")):
        gt = json.loads(gt_path.read_text(encoding="utf-8"))
        if gt["gemelo_de"]:
            continue
        expected = {d["actividad"]: d for d in gt["obra"]["desviaciones"]}
        try:
            r = analyse_tender(
                split_dir / "tenders" / gt["id"], model, core, gt["planning"]["fecha_inicio"], rates
            )
        except ModelFailed:
            r = None
        if r is None or r.progress is None:
            # no analysis for this tender: every planted delay and alarm counts as missed
            planted += sum(1 for e in expected.values() if e["retraso_dias"])
            alarm_total += len(expected)
            impact_total += 1
            continue
        got = {d.activity: d for d in r.progress.deviations}
        for act, e in expected.items():
            g = got.get(act)
            if e["retraso_dias"]:
                planted += 1
                detected += bool(g and g.delay_days > 0)
                if g and g.delay_days > 0:
                    if g.delay_is_lower_bound:
                        lower_bounds += 1
                    else:
                        exact_total += 1
                        exact_ok += g.delay_days == e["retraso_dias"]
            elif g and g.delay_days > 0:
                false_flags += 1
            alarm_total += 1
            alarm_ok += bool(g) and g.alarm == e["alarma"]
        impact_total += 1
        impact_ok += r.progress.impact_days == gt["obra"]["impacto_fin_obra_dias"]
        reports += sum(1 for k in r.reports if k != "rfi")
        rfis += r.reports.count("rfi")
    return {
        "split": split_dir.name,
        "date": time.strftime("%Y-%m-%d"),
        "planted_delays": planted,
        "detected": detected,
        "false_delay_flags": false_flags,
        "alarms_agree": alarm_ok,
        "alarm_rows": alarm_total,
        "delay_exact": exact_ok,
        "delay_measurable": exact_total,
        "delay_lower_bound_only": lower_bounds,
        "impact_exact": impact_ok,
        "tenders": impact_total,
        "site_reports": reports,
        "rfis": rfis,
        "unsupported_claims": 0,
    }


def render(dev: dict, ev: dict, label: str) -> str:
    def row(name: str, a: str, b: str) -> str:
        return f"| {name} | {ev[a]}/{ev[b]} | {dev[a]}/{dev[b]} |"

    return "\n".join(
        [
            "# Site progress eval",
            "",
            f"Generated {ev['date']} by `make eval-site`. Source: **{label}**. Deviations, SPI, delays, "
            "alarms and impact are computed by code from the weekly progress file and the schedule.",
            "",
            "| Metric | Eval | Dev |",
            "|---|---|---|",
            row("Planted delays detected", "detected", "planted_delays"),
            f"| Activities wrongly flagged as delayed | {ev['false_delay_flags']} | {dev['false_delay_flags']} |",
            row("Alarm (delay beyond float) agrees with reference", "alarms_agree", "alarm_rows"),
            row("Delay days exact (activity already started)", "delay_exact", "delay_measurable"),
            f"| Delays known only as a lower bound (activity not started) | {ev['delay_lower_bound_only']} | "
            f"{dev['delay_lower_bound_only']} |",
            row("Impact on the project end exact", "impact_exact", "tenders"),
            f"| Reports drafted (all sentences with existing sources) | {ev['site_reports']} | {dev['site_reports']} |",
            f"| RFIs drafted from gaps and contradictions | {ev['rfis']} | {dev['rfis']} |",
            "",
            "Every report passes `check_sources` before it is sent to the core; a report with an "
            "unsupported claim raises an error instead, so the count of unsupported claims is 0 by "
            "construction and the run would fail otherwise.",
            "",
        ]
    )


def evaluate(data_dir: Path, model: Model, out_dir: Path, label: str) -> dict:
    dev = run(data_dir / "dev", model)
    ev = run(data_dir / "eval", model)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "site.json").write_text(
        json.dumps({"dev": dev, "eval": ev}, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (out_dir / "site.md").write_text(render(dev, ev, label), encoding="utf-8")
    return {"dev": dev, "eval": ev}
