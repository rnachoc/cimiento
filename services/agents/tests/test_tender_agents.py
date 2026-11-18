from __future__ import annotations

from decimal import Decimal

import pytest

from cimiento_agents.citations import Citation, TenderDocs
from cimiento_agents.coordinator import analyse_tender
from cimiento_agents.core_client import RecordingCore
from cimiento_agents.ingest.chunking import chunk_pages
from cimiento_agents.llm import FakeModel
from cimiento_agents.proposal_writer import proposal_draft
from cimiento_agents.qa import NO_CONSTA, answer
from cimiento_agents.search.models import HashEmbedder, OverlapReranker
from cimiento_agents.search.pipeline import SearchPipeline
from cimiento_agents.search.store import InMemoryStore
from cimiento_agents.takeoff import TakeoffError, TakeoffReading, TakeoffRow, compute_items
from cimiento_agents.tender_agents import bid_no_bid, load_rates, risk_signals

C = Citation(document="mediciones", page=1, text="fila")
UNITS = {"MT01": "m3", "IS01": "m", "UR01": "m2"}


def row(code, unit, qty, dup=False):
    return TakeoffRow(code=code, unit_text=unit, quantity_text=qty, duplicate=dup, citation=C)


def test_takeoff_converts_units_and_skips_duplicates():
    items = compute_items(
        TakeoffReading(
            rows=[
                row("MT01", "m³", "100,25"),
                row("MT01", "M3", "1.200,25"),
                row("MT01", "M3", "1.200,25", True),
                row("IS01", "km", "0,350"),
                row("IS01", "ml", "50"),
                row("UR01", "ha", "0,3500"),
            ]
        ),
        UNITS,
    )
    got = {i["code"]: Decimal(i["quantity"]) for i in items}
    assert got == {"MT01": Decimal("1300.50"), "IS01": Decimal("400"), "UR01": Decimal("3500")}
    mt = next(i for i in items if i["code"] == "MT01")
    assert [r["duplicate"] for r in mt["rows"]] == [False, False, True]


@pytest.mark.parametrize(
    "bad", [row("MT01", "yd3", "1"), row("MT01", "m2", "1"), row("ZZ99", "m3", "1")]
)
def test_takeoff_rejects_unknown_or_wrong_units(bad):
    with pytest.raises(TakeoffError):
        compute_items(TakeoffReading(rows=[bad]), UNITS)


def cit(name):
    return Citation(document="pliego", page=1, text=name)


def test_risk_signals_and_bid_rules():
    fields = {
        "plazo_ejecucion_meses": "12",
        "anexo_plazo_ejecucion_meses": "15",
        "penalidad_demora_eur_por_mil_dia": "2.00",
        "subcontratacion_max_pct": "40",
        "presupuesto_base_eur": "1000000.00",
        "solvencia_economica_eur": "500000.00",
        "solvencia_tecnica_obras": "3",
    }
    cites = {k: cit(k) for k in fields}
    risks = risk_signals(fields, cites, [])
    kinds = sorted(r.kind for r in risks)
    assert kinds == ["CONTRADICTION", "DEMANDING_CLAUSE", "DEMANDING_CLAUSE"]
    contradiction = next(r for r in risks if r.kind == "CONTRADICTION")
    assert len(contradiction.citations) == 2 and contradiction.severity == "HIGH"
    # a contradiction always goes to a person
    assert bid_no_bid(fields, Decimal("900000"), risks).recommendation == "REVIEW"
    clean = [r for r in risks if r.kind != "CONTRADICTION"]
    assert bid_no_bid(fields, Decimal("900000"), clean).recommendation == "BID"
    low_margin = bid_no_bid(fields, Decimal("990000"), clean)
    assert low_margin.recommendation == "NO_BID" and low_margin.margin_pct == "1.0"
    too_big = bid_no_bid(
        {**fields, "solvencia_economica_eur": "9000000.00"}, Decimal("900000"), clean
    )
    assert too_big.recommendation == "NO_BID"
    assert bid_no_bid(fields, None, clean).reasons[0] == "budget or base price missing"


def test_injection_risk_forces_review():
    injection = cit("instrucción")
    risks = risk_signals({}, {}, [injection])
    assert [r.kind for r in risks] == ["INJECTION"]
    assert bid_no_bid({}, None, risks).recommendation == "REVIEW"


def test_proposal_draft_makes_no_claim_without_a_source():
    fields = {"plazo_ejecucion_meses": "12", "visita_obligatoria": "no"}
    draft = proposal_draft("D-1", fields, {k: cit(k) for k in fields})
    assert [s["title"] for s in draft] == ["Objeto", "Plazo"]
    assert draft[1]["source"]["text"] == "plazo_ejecucion_meses"
    assert "12 meses" in draft[1]["text"]


def test_coordinator_runs_every_tender_agent(dev_split):
    core = RecordingCore()
    rates = load_rates(dev_split / "company" / "tarifas.json")
    run = analyse_tender(
        dev_split / "tenders" / "D-001", FakeModel(None), core, "2027-01-04", rates
    )
    types = [p["type"] for p in core.proposals]
    assert types[:3] == ["BID_ANALYSIS", "TAKEOFF", "BUDGET"]
    assert {"REPORT", "DOCUMENT_DRAFT"} <= set(types)
    budget = next(p for p in core.proposals if p["type"] == "BUDGET")
    assert budget["payload"]["claimedTotal"] == run.budget_total
    assert run.recommendation in {"BID", "NO_BID", "REVIEW"}
    assert all(p["citations"] for p in core.proposals)


def qa_setup():
    pages = [
        "Cláusula 1. Plazo de ejecución\nEl plazo de ejecución será de doce (12) meses.",
        "Cláusula 2. Garantías\nGarantía definitiva del 5 % del importe.",
    ]
    store = InMemoryStore()
    chunks = chunk_pages("T-9", "pliego", pages)
    emb = HashEmbedder()
    store.add(chunks, emb.embed([c.text for c in chunks]))
    docs = TenderDocs("T-9", {"pliego": pages})
    return docs, SearchPipeline(store, emb, OverlapReranker(), thresholds={"rerank": 0.5})


def test_qa_answers_with_verified_citation_or_no_consta():
    docs, pipe = qa_setup()
    a = answer("¿Cuál es el plazo de ejecución?", docs, pipe, FakeModel(None))
    assert a.found and a.citation.page == 1 and "doce (12) meses" in a.answer
    assert docs.verify(a.citation)
    none = answer("¿Qué horario de trabajo se permite?", docs, pipe, FakeModel(None))
    assert not none.found and none.answer == NO_CONSTA and none.citation is None


def test_qa_refuses_answers_with_fabricated_citations():
    from cimiento_agents.llm import ModelResult
    from cimiento_agents.qa import Answer

    class Fabricating:
        def run(self, task, system, user, schema, rule_based=None):
            return ModelResult(
                Answer(
                    answer="18 meses",
                    found=True,
                    citation=Citation(document="pliego", page=1, text="18 meses"),
                ),
                "claude",
                "test",
            )

    docs, pipe = qa_setup()
    a = answer("¿Cuál es el plazo de ejecución?", docs, pipe, Fabricating())
    assert not a.found and a.answer == NO_CONSTA
