"""Progress analyst: weekly progress and daily reports against the schedule.

Code computes everything: SPI, delay, float, alarm and the impact on the project end. The
schedule comes from the planner (the same payload the core validated). A small CPM pass is
needed here to know float and impact; it follows the core's definitions and is tested.

Delay model: an activity that started late runs at its planned pace. With progress p % at
the cut (c working days elapsed), delay = (c - ES) - p * duration / 100, rounded. An activity
that should have started but shows 0 % has a delay of at least c - ES days (a lower bound).
"""

from __future__ import annotations

import csv
import re
from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

_INCIDENT = re.compile(r"Retraso en el inicio de (A\d{2})\b")


@dataclass(frozen=True)
class Times:
    es: int
    ef: int
    ls: int
    lf: int

    @property
    def total_float(self) -> int:
        return self.ls - self.es


def cpm(schedule: dict) -> tuple[int, dict[str, Times]]:
    durations = {a["code"]: a["duration"] for a in schedule["activities"]}
    preds: dict[str, list[tuple[str, int]]] = {a: [] for a in durations}
    succs: dict[str, list[tuple[str, int]]] = {a: [] for a in durations}
    for lk in schedule["links"]:
        preds[lk["to"]].append((lk["from"], lk["lag"]))
        succs[lk["from"]].append((lk["to"], lk["lag"]))
    order: list[str] = []
    indeg = {a: len(p) for a, p in preds.items()}
    ready = sorted(a for a, d in indeg.items() if d == 0)
    while ready:
        a = ready.pop(0)
        order.append(a)
        for s, _ in succs[a]:
            indeg[s] -= 1
            if indeg[s] == 0:
                ready = sorted([*ready, s])
    if len(order) != len(durations):
        raise ValueError("dependency cycle")
    es: dict[str, int] = {}
    for a in order:
        es[a] = max((es[p] + durations[p] + lag for p, lag in preds[a]), default=0)
    end = max(es[a] + durations[a] for a in order)
    lf: dict[str, int] = {}
    for a in reversed(order):
        lf[a] = min((lf[s] - durations[s] - lag for s, lag in succs[a]), default=end)
    return end, {a: Times(es[a], es[a] + durations[a], lf[a] - durations[a], lf[a]) for a in order}


@dataclass
class Deviation:
    activity: str
    planned_pct: int
    actual_pct: int
    spi: str | None
    delay_days: int
    delay_is_lower_bound: bool
    total_float: int
    critical: bool
    alarm: bool
    source: str  # record id of the weekly row


@dataclass
class ProgressReport:
    tender: str
    week: int
    cut_date: str
    deviations: list[Deviation]
    impact_days: int
    report_dates: list[str]
    incidents: list[dict] = field(default_factory=list)  # from daily reports, with record ids

    @property
    def record(self) -> str:
        return f"avance:semana{self.week}"


def read_weekly(path: Path) -> list[dict]:
    with open(path, encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh, delimiter=";"))


def read_daily(folder: Path) -> list[dict]:
    out = []
    for p in sorted(folder.glob("*.txt")):
        text = p.read_text(encoding="utf-8")
        date = re.search(r"^Fecha: (\S+)$", text, re.M)
        for m in _INCIDENT.finditer(text):
            line = next(ln for ln in text.splitlines() if m.group(0) in ln)
            out.append(
                {
                    "record": f"parte:{date.group(1) if date else p.stem}",
                    "activity": m.group(1),
                    "text": line,
                }
            )
    return out


def analyse(tender: str, site_dir: Path, schedule: dict) -> ProgressReport:
    rows = read_weekly(site_dir / "avance_semanal.csv")
    week = max(int(r["semana"]) for r in rows)
    last = {r["actividad"]: r for r in rows if int(r["semana"]) == week}
    cut = next(iter(last.values()))["fecha_corte"]
    c = 5 * week
    end, times = cpm(schedule)
    durations = {a["code"]: a["duration"] for a in schedule["activities"]}
    deviations: list[Deviation] = []
    delays: dict[str, int] = {}
    for act in sorted(last):
        if act not in times:
            continue
        planned, actual = (
            int(last[act]["avance_planificado_pct"]),
            int(last[act]["avance_real_pct"]),
        )
        if planned == 0 and actual == 0:
            continue
        t, dur = times[act], durations[act]
        lower = False
        if actual >= planned:
            delay = 0
        elif actual == 0:
            delay, lower = c - t.es, True
        else:
            elapsed = Decimal(actual) * dur / 100
            delay = int((Decimal(c - t.es) - elapsed).to_integral_value(ROUND_HALF_UP))
            delay = max(delay, 1)
        if delay:
            delays[act] = delay
        spi = (
            None
            if planned == 0
            else str((Decimal(actual) / Decimal(planned)).quantize(Decimal("0.01"), ROUND_HALF_UP))
        )
        deviations.append(
            Deviation(
                act,
                planned,
                actual,
                spi,
                delay,
                lower,
                t.total_float,
                t.total_float == 0,
                delay > t.total_float,
                f"avance:semana{week}:{act}",
            )
        )
    shifted = {
        **schedule,
        "activities": [
            {**a, "duration": a["duration"] + delays.get(a["code"], 0)}
            for a in schedule["activities"]
        ],
    }
    impact = cpm(shifted)[0] - end
    incidents = read_daily(site_dir / "partes")
    dates = sorted(p.stem for p in (site_dir / "partes").glob("*.txt"))
    return ProgressReport(tender, week, cut, deviations, impact, dates, incidents)


def progress_payload(r: ProgressReport) -> dict:
    """PROGRESS_UPDATE payload for the core (strict schema: week and rows)."""
    return {
        "week": r.week,
        "rows": [
            {"activity": d.activity, "plannedPct": d.planned_pct, "actualPct": d.actual_pct}
            for d in r.deviations
        ],
    }
