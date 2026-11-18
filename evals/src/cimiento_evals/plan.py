"""Planning eval: proposed schedules against the reference schedule of every tender.

Validity is checked with the reference solver (positive durations, known activities, no
cycles), the same rules the core applies. Duration error and critical path agreement are
measured against the ground truth. The term check uses the ground-truth contract term and
start date, so a wrongly extracted term does not leak into this metric.
"""

from __future__ import annotations

import json
import statistics
import time
from datetime import date
from pathlib import Path

from cimiento_agents.coordinator import analyse_tender
from cimiento_agents.core_client import RecordingCore
from cimiento_agents.llm import Model
from cimiento_agents.tender_agents import load_rates
from cimiento_data.planning import contract_deadline, workday_date
from cimiento_data.solver import ScheduleError, solve


def run(split_dir: Path, model: Model) -> dict:
    rates = load_rates(split_dir / "company" / "tarifas.json")
    core = RecordingCore()
    rows = []
    for gt_path in sorted((split_dir / "ground_truth").glob("*.json")):
        gt = json.loads(gt_path.read_text(encoding="utf-8"))
        if gt["gemelo_de"]:
            continue
        plan = gt["planning"]
        r = analyse_tender(
            split_dir / "tenders" / gt["id"], model, core, plan["fecha_inicio"], rates
        )
        row = {"tender": gt["id"], "proposed": r.schedule is not None, "valid": False}
        if r.schedule:
            durations = {a["code"]: a["duration"] for a in r.schedule["activities"]}
            links = [(lk["from"], lk["to"], lk["lag"]) for lk in r.schedule["links"]]
            try:
                res = solve(durations, links)
            except ScheduleError:
                res = None
            if res is not None:
                start = date.fromisoformat(plan["fecha_inicio"])
                end = workday_date(start, res.duration - 1)
                exceeds = end > contract_deadline(start, plan["plazo_contractual_meses"])
                expected = plan["duracion_dias_laborables"]
                row.update(
                    valid=True,
                    duration=res.duration,
                    expected=expected,
                    abs_error_days=abs(res.duration - expected),
                    abs_error_pct=abs(res.duration - expected) / expected * 100,
                    critical_exact=res.critical == plan["actividades_criticas"],
                    critical_jaccard=len(set(res.critical) & set(plan["actividades_criticas"]))
                    / len(set(res.critical) | set(plan["actividades_criticas"])),
                    exceeds=exceeds,
                    exceeds_expected=plan["supera_plazo"],
                )
        rows.append(row)
    valid = [r for r in rows if r["valid"]]
    n = len(rows)
    return {
        "split": split_dir.name,
        "date": time.strftime("%Y-%m-%d"),
        "tenders": n,
        "proposed": sum(r["proposed"] for r in rows),
        "valid": len(valid),
        "duration_mean_abs_error_days": round(
            statistics.mean(r["abs_error_days"] for r in valid), 2
        )
        if valid
        else None,
        "duration_mean_abs_error_pct": round(statistics.mean(r["abs_error_pct"] for r in valid), 2)
        if valid
        else None,
        "duration_exact": sum(r["abs_error_days"] == 0 for r in valid),
        "critical_path_exact": sum(r["critical_exact"] for r in valid),
        "critical_path_mean_jaccard": round(
            statistics.mean(r["critical_jaccard"] for r in valid), 3
        )
        if valid
        else None,
        "term_flag_agrees": sum(r["exceeds"] == r["exceeds_expected"] for r in valid),
        "term_exceeded_expected": sum(r["exceeds_expected"] for r in valid),
        "rows": rows,
    }


def render(dev: dict, ev: dict, label: str) -> str:
    def line(name: str, key: str, fmt=lambda d, k: f"{d[k]}/{d['tenders']}") -> str:
        return f"| {name} | {fmt(ev, key)} | {fmt(dev, key)} |"

    return "\n".join(
        [
            "# Planning eval",
            "",
            f"Generated {ev['date']} by `make eval-plan`. Source of answers: **{label}**.",
            "",
            "Schedules are checked with the reference solver (no cycles, positive durations, known "
            "activities) and compared with the reference schedule of each tender. The reference "
            "follows the generator's productivity and dependency rules, so these numbers measure "
            "agreement with those rules, not with real sites.",
            "",
            "| Metric | Eval | Dev |",
            "|---|---|---|",
            line("Schedules proposed", "proposed"),
            line("Valid schedules", "valid"),
            line("Total duration exact", "duration_exact"),
            f"| Mean absolute duration error | {ev['duration_mean_abs_error_days']} days "
            f"({ev['duration_mean_abs_error_pct']} %) | {dev['duration_mean_abs_error_days']} days "
            f"({dev['duration_mean_abs_error_pct']} %) |",
            line("Critical path identical", "critical_path_exact"),
            f"| Critical path overlap (mean Jaccard) | {ev['critical_path_mean_jaccard']} | "
            f"{dev['critical_path_mean_jaccard']} |",
            line("Contract-term flag agrees with reference", "term_flag_agrees"),
            "",
            "When the contract term is not found, the tender is still registered and the core "
            "sends its schedule to a person (CONTRACT_TERM warning).",
            "",
        ]
    )


def evaluate(data_dir: Path, model: Model, out_dir: Path, label: str) -> dict:
    dev = run(data_dir / "dev", model)
    ev = run(data_dir / "eval", model)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "plan.json").write_text(
        json.dumps({"dev": dev, "eval": ev}, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (out_dir / "plan.md").write_text(render(dev, ev, label), encoding="utf-8")
    return {"dev": dev, "eval": ev}
