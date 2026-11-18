"""Coordinator in code (not a model): decides which agents run and in which order.

Phase 4a runs the bid analyst. Results always go to the core as proposals; the core
validates and routes them. Nothing here can approve or execute anything.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from . import telemetry, tracing
from .bid_analyst import SYSTEM, BidExtraction, ExtractedField, rule_based_extract, user_prompt
from .citations import SITE_DOCUMENT, Citation, TenderDocs
from .core_client import Core
from .injection import detect
from .llm import Model
from .planner import DEPENDENCIES, PlanChoice, build_schedule, crew_days, rule_based_choice
from .planner import SYSTEM as PLANNER_SYSTEM
from .progress import ProgressReport, analyse, progress_payload
from .proposal_writer import proposal_draft
from .reports import check_sources, daily_report, known_records, minutes, rfi, weekly_report
from .takeoff import SYSTEM as TAKEOFF_SYSTEM
from .takeoff import TakeoffError, TakeoffReading, compute_items, rule_based_read
from .tender_agents import Rate, bid_no_bid, budget_lines, risk_signals
from .textnorm import normalize

# One span per agent (the model calls inside get their own gen_ai spans).
compute_items = tracing.traced("agent.takeoff_analyst")(compute_items)
build_schedule = tracing.traced("agent.planner")(build_schedule)
budget_lines = tracing.traced("agent.cost_estimator")(budget_lines)
risk_signals = tracing.traced("agent.risk_analyst")(risk_signals)
bid_no_bid = tracing.traced("agent.bid_no_bid")(bid_no_bid)
proposal_draft = tracing.traced("agent.proposal_writer")(proposal_draft)
analyse = tracing.traced("agent.progress_analyst")(analyse)
weekly_report = tracing.traced("agent.report_writer")(weekly_report)
minutes = tracing.traced("agent.report_writer")(minutes)
daily_report = tracing.traced("agent.report_writer")(daily_report)
rfi = tracing.traced("agent.report_writer")(rfi)

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
    takeoff: dict[str, str] = field(default_factory=dict)
    takeoff_dropped: int = 0
    budget_total: str | None = None
    risks: list[tuple[str, str]] = field(default_factory=list)
    recommendation: str | None = None
    errors: list[str] = field(default_factory=list)
    schedule: dict | None = None
    progress: ProgressReport | None = None
    reports: list[str] = field(default_factory=list)


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


def _analyse_tender(
    folder: Path, model: Model, core: Core, planned_start: str, rates: dict[str, Rate] | None = None
) -> TenderRun:
    """Bid analysis always; with company rates also takeoff, budget, risks, bid/no-bid
    and the proposal draft."""
    docs = TenderDocs.load(folder)
    with (
        telemetry.timed("bid_analysis", tender=docs.tender) as t,
        tracing.span("agent.bid_analyst", **{"cimiento.tender.id": docs.tender}),
    ):
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
    term_text = run.fields.get("plazo_ejecucion_meses")
    term = int(term_text) if term_text and term_text.isdigit() and int(term_text) > 0 else None
    if term is None:
        # The core accepts an unknown term; its CONTRACT_TERM rule then forces human review.
        run.errors.append("contract term not found")
        telemetry.event("contract_term_missing", tender=docs.tender)
    tender = core.register_tender(
        PROJECT, docs.tender, f"Licitación {docs.tender}", term, planned_start
    )
    payload = {
        "fields": run.fields,
        # where each field was read; the core stores JSON with sorted keys, so order cannot carry it
        "sources": {
            name: {"document": c.document, "page": c.page} for name, c in run.citations.items()
        },
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
        analyse_site(run, folder, core, tender["id"])
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
        cd = crew_days(items, rates)
        plan_prompt = (
            "Activities and crew-days: "
            + ", ".join(f"{a} {float(v):.1f}" for a, v in sorted(cd.items()))
            + "\nTypical rules (from, to, lag): "
            + ", ".join(f"{p}->{s} {lag}" for p, s, lag in DEPENDENCIES)
        )
        choice = model.run(
            "planning",
            PLANNER_SYSTEM,
            plan_prompt,
            PlanChoice,
            lambda: rule_based_choice(items, rates),
        ).output
        try:
            schedule = build_schedule(items, rates, choice)  # type: ignore[arg-type]
        except ValueError as e:
            run.errors.append(f"planning: {e}")
        else:
            run.schedule = schedule
            p = core.submit(tender_id, "SCHEDULE", schedule, row_cites[:1], signals)
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

    rfi_report = rfi(run.not_found, [list(r.citations) for r in risks if r.kind == "CONTRADICTION"])
    if rfi_report is not None:
        check_sources(
            rfi_report,
            known_records(
                None,
                list(run.citations.values()) + [c for r in risks for c in r.citations],
                run.not_found,
            ),
        )
        p = core.submit(tender_id, "REPORT", rfi_report.payload(), cites or row_cites[:1], signals)
        run.proposal_ids.append(p["id"])
        run.reports.append("rfi")


def analyse_site(run: TenderRun, folder: Path, core: Core, tender_id: int) -> None:
    """Phase 6: progress against the schedule and the site reports (drafts)."""
    site = folder / "obra"
    if run.schedule is None or not (site / "avance_semanal.csv").exists():
        return
    progress = analyse(run.tender, site, run.schedule)
    run.progress = progress
    # Cite a literal line of the weekly file: the first row of the status week.
    week_row = next(
        line
        for line in (site / "avance_semanal.csv").read_text(encoding="utf-8").splitlines()
        if line.startswith(f"{progress.week};{progress.cut_date};")
    )
    csv_cite = [{"document": SITE_DOCUMENT, "page": 1, "text": week_row}]
    p = core.submit(tender_id, "PROGRESS_UPDATE", progress_payload(progress), csv_cite, [])
    run.proposal_ids.append(p["id"])
    known = known_records(progress, [], [])
    reports = [weekly_report(progress), minutes(progress)]
    if progress.report_dates:
        reports.append(daily_report(progress, f"parte:{progress.report_dates[-1]}"))
    for rep in reports:
        check_sources(rep, known)  # raises: no report with an unsupported claim reaches the core
        p = core.submit(tender_id, "REPORT", rep.payload(), csv_cite, [])
        run.proposal_ids.append(p["id"])
        run.reports.append(rep.kind)


def analyse_tender(
    folder: Path, model: Model, core: Core, planned_start: str, rates: dict[str, Rate] | None = None
) -> TenderRun:
    """One trace per tender: every agent, model call and core request below this span."""
    with tracing.span("cimiento.tender", **{"cimiento.tender.id": folder.name}) as s:
        run = _analyse_tender(folder, model, core, planned_start, rates)
        tracing.set_attributes(
            s, **{"cimiento.proposals": len(run.proposal_ids), "cimiento.source": run.source}
        )
        return run
