"""Site progress: weekly progress per activity and daily reports with planted delays.

Model: a delayed activity starts `d` working days late and then runs at its planned
pace. Progress is measured at the end of a week; `c` is the number of working days
elapsed (week k ends after c = 5k days).
"""

from __future__ import annotations

import random
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal

from .catalog import ACTIVITIES
from .solver import CpmResult, solve

MAX_DELAY = 6  # working days

DELAY_CAUSES = (
    "falta de suministro de material",
    "baja de personal de la subcontrata",
    "lluvias que impiden trabajar",
    "retraso en la entrega de planos de detalle",
    "avería de la maquinaria",
)


def pct(elapsed: int, duration: int) -> int:
    x = Decimal(max(0, min(elapsed, duration)) * 100) / Decimal(duration)
    return int(x.to_integral_value(rounding=ROUND_HALF_UP))


@dataclass(frozen=True)
class Delay:
    activity: str
    days: int
    cause: str


@dataclass(frozen=True)
class SitePlan:
    status_week: int
    delays: tuple[Delay, ...]

    @property
    def status_days(self) -> int:
        return 5 * self.status_week

    def delay_of(self, act: str) -> int:
        return next((d.days for d in self.delays if d.activity == act), 0)


def in_progress(cpm: CpmResult, c: int) -> list[str]:
    return [a.id for a in cpm.activities.values() if a.es < c < a.ef]


def plan_site(cpm: CpmResult, r: random.Random) -> SitePlan:
    """Pick a status week with a critical activity in progress and plant delays."""
    last_week = (cpm.duration - 1) // 5
    lo = max(1, (cpm.duration * 3) // 10 // 5)
    hi = max(lo, min(last_week, -(-cpm.duration * 7 // 10) // 5))
    candidates = list(range(lo, hi + 1))
    r.shuffle(candidates)
    candidates += [k for k in range(1, last_week + 1) if k not in candidates]
    # Half of the tenders try to plant an alarming non-critical delay. That needs a
    # running non-critical activity whose float is below MAX_DELAY, so prefer a week
    # where one exists; otherwise fall back to the first valid week.
    want_alarm = r.random() < 0.5

    def small_float(running: list[str]) -> list[str]:
        return sorted(a for a in running if 0 < cpm.activities[a].total_float < MAX_DELAY)

    valid = [
        k for k in candidates if any(cpm.activities[a].critical for a in in_progress(cpm, 5 * k))
    ]
    if want_alarm:
        valid.sort(key=lambda k: not small_float(in_progress(cpm, 5 * k)))  # stable
    for week in valid:
        c = 5 * week
        running = in_progress(cpm, c)
        critical = sorted(a for a in running if cpm.activities[a].critical)
        delays = [Delay(r.choice(critical), r.randint(2, MAX_DELAY), r.choice(DELAY_CAUSES))]
        non_critical = sorted(a for a in running if not cpm.activities[a].critical)
        if non_critical:
            # Delays stay realistic (at most MAX_DELAY days). An alarming delay needs an
            # activity whose float is small enough to be exceeded.
            if want_alarm and small_float(running):
                act = r.choice(small_float(running))
                f = cpm.activities[act].total_float
                days = r.randint(f + 1, MAX_DELAY)
            else:
                act = r.choice(non_critical)
                f = cpm.activities[act].total_float
                days = r.randint(1, min(f, MAX_DELAY))
            delays.append(Delay(act, days, r.choice(DELAY_CAUSES)))
        return SitePlan(week, tuple(delays))
    raise ValueError("no week with a critical activity in progress")


def project_impact(durations: dict[str, int], links, site: SitePlan, base: CpmResult) -> int:
    """Extra working days on the project end if each delay shifts its activity."""
    delayed = {a: d + site.delay_of(a) for a, d in durations.items()}
    return solve(delayed, links).duration - base.duration


def weekly_rows(cpm: CpmResult, site: SitePlan) -> list[dict[str, object]]:
    rows = []
    for week in range(1, site.status_week + 1):
        c = 5 * week
        for act in sorted(cpm.activities):
            a = cpm.activities[act]
            rows.append(
                {
                    "semana": week,
                    "actividad": act,
                    "nombre": ACTIVITIES[act],
                    "avance_planificado_pct": pct(c - a.es, a.duration),
                    "avance_real_pct": pct(c - a.es - site.delay_of(act), a.duration),
                }
            )
    return rows


def deviations(cpm: CpmResult, site: SitePlan) -> list[dict[str, object]]:
    c = site.status_days
    out = []
    for act in sorted(cpm.activities):
        a = cpm.activities[act]
        planned = pct(c - a.es, a.duration)
        real = pct(c - a.es - site.delay_of(act), a.duration)
        if planned == 0 and real == 0:
            continue
        d = site.delay_of(act)
        out.append(
            {
                "actividad": act,
                "avance_planificado_pct": planned,
                "avance_real_pct": real,
                "spi": None
                if planned == 0
                else str(
                    (Decimal(real) / Decimal(planned)).quantize(Decimal("0.01"), ROUND_HALF_UP)
                ),
                "retraso_dias": d,
                "holgura_total": a.total_float,
                "critica": a.critical,
                "alarma": d > a.total_float,
            }
        )
    return out


def daily_reports(cpm: CpmResult, site: SitePlan, r: random.Random, window: int = 10):
    """Reports for the last `window` working days before the status cut."""
    c = site.status_days
    reports = []
    for day in range(max(0, c - window), c):
        running, incidents = [], []
        for act in sorted(cpm.activities):
            a = cpm.activities[act]
            d = site.delay_of(act)
            if a.es + d <= day < a.ef + d:
                running.append(act)
            if d and a.es <= day < a.es + d:
                cause = next(x.cause for x in site.delays if x.activity == act)
                incidents.append(
                    {
                        "actividad": act,
                        "texto": f"Retraso en el inicio de {act} {ACTIVITIES[act]} por {cause}.",
                    }
                )
        reports.append(
            {
                "dia": day,
                "actividades_en_curso": running,
                "personal": r.randint(6, 30),
                "incidencias": incidents,
            }
        )
    return reports
