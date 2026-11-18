from __future__ import annotations

from pathlib import Path

import pytest

from cimiento_agents.citations import Citation
from cimiento_agents.progress import analyse, cpm
from cimiento_agents.reports import (
    Report,
    Sentence,
    UnsupportedClaim,
    check_sources,
    daily_report,
    known_records,
    minutes,
    rfi,
    weekly_report,
)

SCHEDULE = {
    "activities": [
        {"code": "A01", "duration": 5},
        {"code": "A02", "duration": 10},
        {"code": "A03", "duration": 4},
    ],
    "links": [{"from": "A01", "to": "A02", "lag": 0}, {"from": "A01", "to": "A03", "lag": 0}],
}


def test_cpm_float_and_end():
    end, t = cpm(SCHEDULE)
    assert end == 15
    assert (t["A02"].es, t["A02"].total_float) == (5, 0)
    assert t["A03"].total_float == 6
    with pytest.raises(ValueError):
        cpm(
            {
                "activities": SCHEDULE["activities"],
                "links": [
                    {"from": "A02", "to": "A01", "lag": 0},
                    {"from": "A01", "to": "A02", "lag": 0},
                ],
            }
        )


def make_site(
    tmp_path: Path, rows: list[tuple[str, int, int]], incident: str | None = None
) -> Path:
    site = tmp_path / "obra"
    (site / "partes").mkdir(parents=True)
    lines = ["semana;fecha_corte;actividad;nombre;avance_planificado_pct;avance_real_pct"]
    lines += [f"2;2027-01-15;{a};x;{p};{r}" for a, p, r in rows]
    (site / "avance_semanal.csv").write_text("\n".join(lines) + "\n", encoding="utf-8")
    body = (
        "PARTE DIARIO DE OBRA\nFecha: 2027-01-15\nIncidencias: "
        + (incident or "Sin incidencias.")
        + "\n"
    )
    (site / "partes" / "2027-01-15.txt").write_text(body, encoding="utf-8")
    return site


def test_delay_spi_alarm_and_impact(tmp_path):
    # cut after 10 working days: A02 planned 50 %, real 30 % -> 2 days late on a critical activity;
    # A03 not started (planned 100 %) -> at least 5 days late, float 6 -> no alarm
    site = make_site(
        tmp_path,
        [("A01", 100, 100), ("A02", 50, 30), ("A03", 100, 0)],
        "Retraso en el inicio de A03 Cubierta por lluvias.",
    )
    r = analyse("T", site, SCHEDULE)
    d = {x.activity: x for x in r.deviations}
    assert (d["A02"].delay_days, d["A02"].delay_is_lower_bound, d["A02"].alarm, d["A02"].spi) == (
        2,
        False,
        True,
        "0.60",
    )
    assert (d["A03"].delay_days, d["A03"].delay_is_lower_bound, d["A03"].alarm) == (5, True, False)
    assert d["A01"].delay_days == 0
    assert r.impact_days == 2
    assert r.incidents == [
        {
            "record": "parte:2027-01-15",
            "activity": "A03",
            "text": "Incidencias: Retraso en el inicio de A03 Cubierta por lluvias.",
        }
    ]


def test_reports_have_sources_for_every_sentence(tmp_path):
    site = make_site(tmp_path, [("A01", 100, 100), ("A02", 50, 30), ("A03", 100, 0)])
    r = analyse("T", site, SCHEDULE)
    known = known_records(r, [], [])
    reports = [weekly_report(r), minutes(r), daily_report(r, "parte:2027-01-15")]
    for rep in reports:
        check_sources(rep, known)
        payload = rep.payload()
        assert payload["status"] == "draft" and payload["title"] and payload["sentences"]
    weekly = reports[0].payload()["sentences"]
    assert any("Alarma" in s["text"] and "avance:semana2:A02" in s["sources"] for s in weekly)
    assert any("al menos 5" in s["text"] for s in weekly)


def test_rfi_from_gaps_and_contradictions():
    a, b = (
        Citation(document="pliego", page=2, text="x"),
        Citation(document="anexo", page=1, text="y"),
    )
    rep = rfi(["fecha_presentacion"], [[a, b]])
    check_sources(rep, known_records(None, [a, b], ["fecha_presentacion"]))
    assert (
        len(rep.sentences) == 2 and "pliego (página 2) y anexo (página 1)" in rep.sentences[1].text
    )
    assert rfi([], []) is None


@pytest.mark.parametrize(
    "report",
    [
        Report("acta", "t", [Sentence("Se acuerda algo.", [])]),
        Report("acta", "t", [Sentence("Se acuerda algo.", ["parte:1999-01-01"])]),
        Report("acta", "t", []),
    ],
)
def test_unsupported_claims_are_rejected(report):
    with pytest.raises(UnsupportedClaim):
        check_sources(report, {"avance:semana1"})


def test_unknown_kind_is_rejected():
    with pytest.raises(ValueError):
        check_sources(Report("poema", "t", [Sentence("x", ["a"])]), {"a"})
