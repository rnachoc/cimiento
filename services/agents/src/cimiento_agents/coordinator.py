"""Coordinator in code (not a model): decides which agents run and in which order.

Phase 4a runs the bid analyst. Results always go to the core as proposals; the core
validates and routes them. Nothing here can approve or execute anything.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from . import telemetry
from .bid_analyst import SYSTEM, BidExtraction, ExtractedField, rule_based_extract, user_prompt
from .citations import Citation, TenderDocs
from .core_client import Core
from .injection import detect
from .llm import Model
from .proposal_writer import proposal_draft
from .takeoff import SYSTEM as TAKEOFF_SYSTEM
from .takeoff import TakeoffError, TakeoffReading, compute_items, rule_based_read
from .tender_agents import Rate, bid_no_bid, budget_lines, risk_signals
from .textnorm import normalize

PROJECT = "LICITACIONES"
SIGNAL_INJECTION = "INJECTION_DETECTED"


@dataclass
class TenderRun:
    tender: str
    source: str
    model: str
    fields: dict[str, str]
    citations: dict[str, Citation]
    dropped_unverified: int
    injections: list[Citation]
    not_found: list[str]
    proposal_ids: list[int] = field(default_factory=list)
    skipped: str | None = None
    takeoff: dict[str, str] = field(default_factory=dict)
    takeoff_dropped: int = 0
    budget_total: str | None = None
    risks: list[tuple[str, str]] = field(default_factory=list)
    recommendation: str | None = None
    errors: list[str] = field(default_factory=list)


def _merge(*groups: list[Citation]) -> list[Citation]:
    seen: set[str] = set()
    out: list[Citation] = []
    for group in groups:
        for c in group:
            key = f"{c.document}:{c.page}:{normalize(c.text)}"
            if key not in seen:
                seen.add(key)
                out.append(c)
    return out


def analyse_tender(
    folder: Path, model: Model, core: Core, planned_start: str, rates: dict[str, Rate] | None = None
) -> TenderRun:
    """Bid analysis always; with company rates also takeoff, budget, risks, bid/no-bid
    and the proposal draft."""
    docs = TenderDocs.load(folder)
    with telemetry.timed("bid_analysis", tender=docs.tender) as t:
        result = model.run(
            "bid_analysis",
            SYSTEM,
            user_prompt(docs),
            BidExtraction,
            lambda: rule_based_extract(docs),
        )
        ext: BidExtraction = result.output  # type: ignore[assignment]
        verified: list[ExtractedField] = []
        seen_names: set[str] = set()
        for f in ext.fields:
            if f.name not in seen_names and docs.verify(f.citation):
                verified.append(f)
                seen_names.add(f.name)
        model_injections = [i.citation for i in ext.injections if docs.verify(i.citation)]
        injections = _merge(detect(docs), model_injections)
        t.update(
            fields=len(verified),
            dropped=len(ext.fields) - len(verified),
            injections=len(injections),
            source=result.source,
        )

    run = TenderRun(
        tender=docs.tender,
        source=result.source,
        model=result.model,
        fields={f.name: f.value for f in verified},
        citations={f.name: f.citation for f in verified},
        dropped_unverified=len(ext.fields) - len(verified),
        injections=injections,
        not_found=[n for n in ext.not_found if n not in seen_names],
    )
    term = run.fields.get("plazo_ejecucion_meses")
    if term is None or not term.isdigit() or int(term) < 1:
        run.skipped = "contract term not found; tender not registered"
        telemetry.event("tender_skipped", tender=docs.tender)
        return run

    tender = core.register_tender(
        PROJECT, docs.tender, f"Licitación {docs.tender}", int(term), planned_start
    )
    payload = {
        "fields": run.fields,
        "notFound": run.not_found,
        "injections": [c.model_dump() for c in injections],
        "source": run.source,
        "model": run.model,
    }
    citations = [c.model_dump() for c in run.citations.values()] + payload["injections"]
    signals = [SIGNAL_INJECTION] if injections else []
    proposal = core.submit(tender["id"], "BID_ANALYSIS", payload, citations, signals)
    run.proposal_ids.append(proposal["id"])
    if rates is not None:
        _tender_agents(run, docs, model, core, tender["id"], rates, signals)
    return run


def _tender_agents(
    run: TenderRun,
    docs: TenderDocs,
    model: Model,
    core: Core,
    tender_id: int,
    rates: dict[str, Rate],
    signals: list[str],
) -> None:
    """Takeoff, budget, risks, bid/no-bid and the proposal draft (phase 4b)."""
    reading = model.run(
        "takeoff", TAKEOFF_SYSTEM, user_prompt(docs), TakeoffReading, lambda: rule_based_read(docs)
    ).output
    rows = [r for r in reading.rows if docs.verify(r.citation)]  # type: ignore[attr-defined]
    run.takeoff_dropped = len(reading.rows) - len(rows)  # type: ignore[attr-defined]
    try:
        items = compute_items(TakeoffReading(rows=rows), {c: r.unit for c, r in rates.items()})
    except TakeoffError as e:
        run.errors.append(f"takeoff: {e}")
        items = []
    if items:
        run.takeoff = {i["code"]: i["quantity"] for i in items}
        row_cites = [r.citation.model_dump() for r in rows]
        p = core.submit(tender_id, "TAKEOFF", {"items": items}, row_cites, signals)
        run.proposal_ids.append(p["id"])
        lines, total = budget_lines(items, rates)
        run.budget_total = str(total)
        p = core.submit(
            tender_id,
            "BUDGET",
            {"lines": lines, "claimedTotal": str(total)},
            row_cites[:1],
            signals,
        )
        run.proposal_ids.append(p["id"])

    risks = risk_signals(run.fields, run.citations, run.injections)
    run.risks = [(r.kind, r.severity) for r in risks]
    for r in risks:
        sig = [SIGNAL_INJECTION] if r.kind == "INJECTION" else signals
        p = core.submit(
            tender_id,
            "RISK_SIGNAL",
            {"kind": r.kind, "severity": r.severity, "description": r.description},
            [c.model_dump() for c in r.citations],
            sig,
        )
        run.proposal_ids.append(p["id"])

    from decimal import Decimal

    decision = bid_no_bid(
        run.fields, Decimal(run.budget_total) if run.budget_total else None, risks
    )
    run.recommendation = decision.recommendation
    cites = [c.model_dump() for c in run.citations.values()]
    p = core.submit(
        tender_id,
        "REPORT",
        {
            "kind": "bid_no_bid",
            "recommendation": decision.recommendation,
            "reasons": decision.reasons,
            "marginPct": decision.margin_pct,
            "budgetTotal": run.budget_total,
        },
        cites,
        signals,
    )
    run.proposal_ids.append(p["id"])

    draft = proposal_draft(docs.tender, run.fields, run.citations)
    p = core.submit(
        tender_id, "DOCUMENT_DRAFT", {"kind": "memoria_tecnica", "sections": draft}, cites, signals
    )
    run.proposal_ids.append(p["id"])
