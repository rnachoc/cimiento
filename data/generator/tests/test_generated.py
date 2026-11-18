"""Checks on the generated splits against their own ground truth."""

from __future__ import annotations

import csv
import hashlib
import json
import os
import re
import subprocess
import sys
from decimal import Decimal

import networkx as nx
import pytest
from conftest import SEEDS, pdf_pages, txt_pages

from cimiento_data.catalog import (
    AUTHORITIES,
    CONTRACTOR,
    TOWNS,
    UNANSWERABLE,
    UNIT_SPELLINGS,
)
from cimiento_data.generate import generate
from cimiento_data.injections import INJECTIONS
from cimiento_data.planning import contract_deadline, workday_date
from cimiento_data.solver import solve
from cimiento_data.templates import ANNEX_TERM, FIELDS, NUMBERING_STYLES, VALUE_FORMATS
from cimiento_data.textnorm import normalize, parse_es

SPLITS = ("dev", "eval")
EVAL_ONLY = (
    {v.id for f in FIELDS for v in f.variants if v.eval_only}
    | {v.id for fmts in VALUE_FORMATS.values() for v in fmts if v.eval_only}
    | {v.id for v in ANNEX_TERM if v.eval_only}
    | {s for s, e in NUMBERING_STYLES if e}
    | {i.id for i in INJECTIONS if i.eval_only}
)


def base_ids(truth: dict) -> list[str]:
    return [k for k, g in truth.items() if g["gemelo_de"] is None]


# ---------------------------------------------------------------- reproducibility


def test_same_seed_gives_identical_files(splits, tmp_path):
    # A separate process with a different hash seed catches state fixed at import time
    # and any dependence on set or dict ordering.
    again = tmp_path / "again"
    env = {**os.environ, "PYTHONHASHSEED": "12345"}
    cmd = [
        sys.executable,
        "-m",
        "cimiento_data.cli",
        "--seed",
        str(SEEDS["dev"]),
        "--split",
        "dev",
        "--out",
        str(again),
    ]
    subprocess.run(cmd, check=True, env=env, capture_output=True)
    first = sorted(p.relative_to(splits["dev"]) for p in splits["dev"].rglob("*") if p.is_file())
    second = sorted(p.relative_to(again) for p in again.rglob("*") if p.is_file())
    assert first == second
    for rel in first:
        assert (splits["dev"] / rel).read_bytes() == (again / rel).read_bytes(), rel


def test_manifest_hashes_match(splits):
    for root in splits.values():
        manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
        files = {p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file()}
        assert set(manifest["ficheros"]) == files - {"manifest.json"}
        for rel, digest in manifest["ficheros"].items():
            assert hashlib.sha256((root / rel).read_bytes()).hexdigest() == digest


def test_refuses_non_empty_output(tmp_path):
    (tmp_path / "x").write_text("x")
    with pytest.raises(FileExistsError):
        generate(1, "dev", tmp_path)


def test_splits_differ(splits):
    dev = json.loads((splits["dev"] / "manifest.json").read_text(encoding="utf-8"))
    ev = json.loads((splits["eval"] / "manifest.json").read_text(encoding="utf-8"))
    assert dev["ficheros"].keys().isdisjoint(k for k in ev["ficheros"] if k.startswith("tenders"))


# ---------------------------------------------------------------- planted cases


@pytest.mark.parametrize("split", SPLITS)
def test_planted_case_counts(truths, split):
    truth = truths[split]
    base = base_ids(truth)
    assert len(base) == 30
    sizes = [truth[t]["tamano"] for t in base]
    assert {s: sizes.count(s) for s in set(sizes)} == {"pequeña": 10, "mediana": 10, "grande": 10}
    assert sum(bool(truth[t]["inyecciones"]) for t in base) == 3  # 10 %
    assert sum(bool(truth[t]["contradicciones"]) for t in base) == 6  # 20 %
    assert sum(bool(truth[t]["partidas_duplicadas"]) for t in base) == 9
    assert sum(truth[t]["planning"]["supera_plazo"] for t in base) == 3
    assert sum(bool(truth[t]["unidades_mezcladas"]) for t in base) > 0


@pytest.mark.parametrize("split", SPLITS)
def test_every_injection_has_a_clean_twin(splits, truths, split):
    truth = truths[split]
    with_inj = [t for t in base_ids(truth) if truth[t]["inyecciones"]]
    twins = [t for t, g in truth.items() if g["gemelo_de"]]
    assert sorted(twins) == sorted(f"{t}-gemelo" for t in with_inj)
    for t in with_inj:
        g, twin = truth[t], truth[f"{t}-gemelo"]
        assert g["gemelo"] == twin["id"] and twin["gemelo_de"] == t
        assert twin["inyecciones"] == []
        inj = g["inyecciones"][0]
        # Same tender: every non-page fact is identical.
        for key in ("presupuesto", "mediciones", "planning", "obra", "contradicciones"):
            if key == "mediciones":
                strip = [{**m, "filas": [{**f, "pagina": 0} for f in m["filas"]]} for m in g[key]]
                strip_t = [
                    {**m, "filas": [{**f, "pagina": 0} for f in m["filas"]]} for m in twin[key]
                ]
                assert strip == strip_t
            elif key == "contradicciones":
                assert [c["a"]["valor"] for c in g[key]] == [c["a"]["valor"] for c in twin[key]]
            else:
                assert g[key] == twin[key], key
        assert {k: v["valor"] for k, v in g["campos"].items()} == {
            k: v["valor"] for k, v in twin["campos"].items()
        }
        # Documents differ only by the injected text.
        for doc in ("pliego", "anexo", "mediciones"):
            a = normalize("".join(txt_pages(splits[split] / "tenders" / t / f"{doc}.txt")))
            b = normalize("".join(txt_pages(splits[split] / "tenders" / twin["id"] / f"{doc}.txt")))
            if doc == inj["documento"]:
                assert normalize(inj["texto"]) in a and normalize(inj["texto"]) not in b
                head = "disposición adicional " if inj["ubicacion"] == "clause" else ""
                assert normalize(a.replace(head + normalize(inj["texto"]), "")) == b
            else:
                assert a == b


@pytest.mark.parametrize("split", SPLITS)
def test_contradictions_are_real(truths, split):
    for g in truths[split].values():
        pliego = g["campos"]["plazo_ejecucion_meses"]["valor"]
        anexo = g["campos"]["anexo_plazo_ejecucion_meses"]["valor"]
        assert bool(g["contradicciones"]) == (pliego != anexo)
        for c in g["contradicciones"]:
            assert c["a"]["documento"] == "pliego" and c["b"]["documento"] == "anexo"
            assert c["a"]["valor"] != c["b"]["valor"] and c["b"]["valor"] >= 1


# ---------------------------------------------------------------- citations


def _check_on_page(root, tid, doc, page, text):
    folder = root / "tenders" / tid
    pages_txt = txt_pages(folder / f"{doc}.txt")
    pages_pdf = pdf_pages(folder / f"{doc}.pdf")
    assert len(pages_txt) == len(pages_pdf)
    assert normalize(text) in normalize(pages_txt[page - 1]), (tid, doc, page, text)
    assert normalize(text) in normalize(pages_pdf[page - 1]), (tid, doc, page, text)


@pytest.mark.parametrize("split", SPLITS)
def test_expected_fields_are_on_their_page(splits, truths, split):
    for tid, g in truths[split].items():
        for campo in g["campos"].values():
            _check_on_page(splits[split], tid, campo["documento"], campo["pagina"], campo["texto"])
            assert campo["pagina"] <= g["documentos"][campo["documento"]]["paginas"]
        for inj in g["inyecciones"]:
            _check_on_page(splits[split], tid, inj["documento"], inj["pagina"], inj["texto"])
        for m in g["mediciones"]:
            for f in m["filas"]:
                _check_on_page(splits[split], tid, f["documento"], f["pagina"], f["texto"])


@pytest.mark.parametrize("split", SPLITS)
def test_clause_numbers_match_headings(splits, truths, split):
    for tid, g in truths[split].items():
        pages = txt_pages(splits[split] / "tenders" / tid / "pliego.txt")
        for name, campo in g["campos"].items():
            if campo["documento"] != "pliego":
                continue
            page = pages[campo["pagina"] - 1]
            before = page[: page.index(campo["texto"])]
            heading = [
                ln for ln in before.splitlines() if re.match(r"^(Cláusula |Artículo )?\d+[.)] ", ln)
            ][-1]
            assert re.search(rf"(^|\s){campo['clausula']}[.)]", heading), (tid, name, heading)


def test_pdf_pages_have_footer_and_fixed_metadata(splits):
    from pypdf import PdfReader

    root = splits["dev"]
    for pdf in sorted((root / "tenders").glob("*/*.pdf"))[:30]:
        reader = PdfReader(pdf)
        n = len(reader.pages)
        for i, page in enumerate(reader.pages, start=1):
            assert f"página {i} de {n}" in page.extract_text()
        assert reader.metadata.creation_date.year == 2026


@pytest.mark.parametrize("split", SPLITS)
def test_pliego_has_several_pages(truths, split):
    for g in truths[split].values():
        assert g["documentos"]["pliego"]["paginas"] >= 2, g["id"]


# ---------------------------------------------------------------- measurements and budget


@pytest.mark.parametrize("split", SPLITS)
def test_measurements_normalise_to_ground_truth(truths, split):
    dup_tenders = 0
    for g in truths[split].values():
        for m in g["mediciones"]:
            total = Decimal(0)
            previous = None
            for f in m["filas"]:
                code, _desc, _loc, unit, qty = [x.strip() for x in f["texto"].split("|")]
                assert code == m["codigo"]
                canon, factor = UNIT_SPELLINGS[unit]
                assert canon == m["unidad"]
                value = parse_es(qty) * factor
                assert value == Decimal(f["cantidad_canonica"])
                if f["duplicada"]:
                    assert previous is not None and previous["texto"] == f["texto"]
                else:
                    total += value
                previous = f
            assert total == Decimal(m["cantidad"]), (g["id"], m["codigo"])
        dups = [f for m in g["mediciones"] for f in m["filas"] if f["duplicada"]]
        assert len(dups) == len(g["partidas_duplicadas"])
        dup_tenders += bool(dups)
    assert dup_tenders > 0


@pytest.mark.parametrize("split", SPLITS)
def test_budget_is_measurements_times_company_rates(splits, truths, split):
    rates = json.loads((splits[split] / "company" / "tarifas.json").read_text(encoding="utf-8"))
    price = {p["codigo"]: Decimal(p["precio_unitario_eur"]) for p in rates["partidas"]}
    assert rates["empresa"] == CONTRACTOR
    for g in truths[split].values():
        total = Decimal(0)
        for m in g["mediciones"]:
            total += (Decimal(m["cantidad"]) * price[m["codigo"]]).quantize(Decimal("0.01"))
        assert total == Decimal(g["presupuesto"]["total"])


# ---------------------------------------------------------------- planning


@pytest.mark.parametrize("split", SPLITS)
def test_reference_planning_is_valid_and_consistent(truths, split):
    from datetime import date

    for g in truths[split].values():
        p = g["planning"]
        durations = {a["id"]: a["duracion"] for a in p["actividades"]}
        links = [(d["de"], d["a"], d["desfase"]) for d in p["dependencias"]]
        graph = nx.DiGraph([(a, b) for a, b, _ in links])
        graph.add_nodes_from(durations)
        assert nx.is_directed_acyclic_graph(graph)
        assert all(d > 0 for d in durations.values())
        res = solve(durations, links)
        assert res.duration == p["duracion_dias_laborables"]
        assert res.critical == p["actividades_criticas"]
        for a in p["actividades"]:
            r = res.activities[a["id"]]
            assert (r.es, r.ef, r.ls, r.lf, r.total_float) == (
                a["es"],
                a["ef"],
                a["ls"],
                a["lf"],
                a["holgura_total"],
            )
        start = date.fromisoformat(p["fecha_inicio"])
        end = workday_date(start, res.duration - 1)
        assert end.isoformat() == p["fecha_fin"]
        deadline = contract_deadline(start, p["plazo_contractual_meses"])
        assert p["supera_plazo"] == (end > deadline)
        assert p["plazo_contractual_meses"] == g["campos"]["plazo_ejecucion_meses"]["valor"]


# ---------------------------------------------------------------- site


@pytest.mark.parametrize("split", SPLITS)
def test_site_progress_matches_ground_truth(splits, truths, split):
    for tid, g in truths[split].items():
        obra = g["obra"]
        acts = {a["id"]: a for a in g["planning"]["actividades"]}
        with open(
            splits[split] / "tenders" / tid / "obra" / "avance_semanal.csv",
            encoding="utf-8",
            newline="",
        ) as fh:
            rows = list(csv.DictReader(fh, delimiter=";"))
        last = {r["actividad"]: r for r in rows if int(r["semana"]) == obra["semana_corte"]}
        assert set(last) == set(acts)
        for d in obra["desviaciones"]:
            row = last[d["actividad"]]
            assert int(row["avance_planificado_pct"]) == d["avance_planificado_pct"]
            assert int(row["avance_real_pct"]) == d["avance_real_pct"]
            a = acts[d["actividad"]]
            assert d["holgura_total"] == a["holgura_total"] and d["critica"] == a["critica"]
            assert d["alarma"] == (d["retraso_dias"] > d["holgura_total"])
        planted = {x["actividad"]: x["dias"] for x in obra["retrasos_plantados"]}
        assert {
            d["actividad"]: d["retraso_dias"] for d in obra["desviaciones"] if d["retraso_dias"]
        } == planted
        assert any(acts[a]["critica"] for a in planted)  # at least one critical delay
        assert all(1 <= v <= 6 for v in planted.values())
        assert obra["impacto_fin_obra_dias"] > 0
        for parte in obra["partes"]:
            text = (splits[split] / "tenders" / tid / parte["fichero"]).read_text(encoding="utf-8")
            assert f"Fecha: {parte['fecha']}" in text
            for inc in parte["incidencias"]:
                assert inc["texto"] in text and inc["actividad"] in planted


@pytest.mark.parametrize("split", SPLITS)
def test_some_noncritical_delays_alarm_and_some_do_not(truths, split):
    flags = [
        (d["critica"], d["alarma"])
        for g in truths[split].values()
        for d in g["obra"]["desviaciones"]
        if d["retraso_dias"]
    ]
    assert (False, True) in flags and (False, False) in flags and (True, True) in flags
    assert (True, False) not in flags


# ---------------------------------------------------------------- held-out wording


def test_dev_never_uses_eval_only_templates(truths):
    used_dev = {t for g in truths["dev"].values() for t in g["plantillas_usadas"]}
    used_eval = {t for g in truths["eval"].values() for t in g["plantillas_usadas"]}
    assert used_dev.isdisjoint(EVAL_ONLY)
    assert len(used_eval & EVAL_ONLY) >= 10


def test_dev_text_never_contains_eval_only_wording(splits):
    eval_phrases = [
        normalize(v.text.split("{v}")[0])
        for f in FIELDS
        for v in f.variants
        if v.eval_only and len(v.text.split("{v}")[0]) > 15
    ]
    eval_phrases += [normalize(i.text) for i in INJECTIONS if i.eval_only]
    assert eval_phrases
    for path in (splits["dev"] / "tenders").glob("*/*.txt"):
        text = normalize(path.read_text(encoding="utf-8"))
        for phrase in eval_phrases:
            assert phrase not in text, (path, phrase)


# ---------------------------------------------------------------- unanswerable and fictional


@pytest.mark.parametrize("split", SPLITS)
def test_unanswerable_questions_have_no_answer_in_documents(splits, truths, split):
    questions = dict(UNANSWERABLE)
    patterns = [re.compile(rf"\b{re.escape(t)}\b") for terms in questions.values() for t in terms]
    for path in (splits[split] / "tenders").rglob("*.txt"):
        text = normalize(path.read_text(encoding="utf-8"))
        for pat in patterns:
            assert not pat.search(text), (path, pat.pattern)
    for g in truths[split].values():
        assert len(g["preguntas_sin_respuesta"]) == 3
        assert set(g["preguntas_sin_respuesta"]) <= set(questions)


@pytest.mark.parametrize("split", SPLITS)
def test_only_fictional_names(truths, split):
    authorities = {a.format(town=t) for a in AUTHORITIES for t in TOWNS}
    for g in truths[split].values():
        assert g["contratista"] == CONTRACTOR
        assert g["municipio"] in TOWNS
        assert g["entidad_contratante"] in authorities
