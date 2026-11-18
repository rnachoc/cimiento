"""Cost estimator, risk analyst and bid/no-bid.

- Cost estimator: maps each measured item to a company rate. The model may only choose the
  rate; the core computes the budget. The estimate shown to agents is recomputed here from
  the same rates for the bid/no-bid rules, and the core checks it (claimedTotal).
- Risk analyst: signals with evidence that code re-checks (contradictions between documents,
  terms the planned work cannot meet, demanding clauses, planted instructions).
- Bid/no-bid: explicit rules in code; a model may only write the narrative.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

from .citations import Citation

# Fictional company profile of Constructora Brisa, used by the bid/no-bid rules.
COMPANY = {
    "annual_turnover_eur": Decimal("6000000.00"),
    "similar_works_last_5_years": 4,
    "min_margin_pct": Decimal("3"),
    "max_penalty_eur_per_mil_day": Decimal("1.00"),
    "min_subcontracting_pct": 50,
}


@dataclass(frozen=True)
class Rate:
    code: str
    unit: str
    unit_price: Decimal
    rate_per_day: Decimal
    activity: str


def load_rates(path: Path) -> dict[str, Rate]:
    """Company rates (company data, not ground truth)."""
    data = json.loads(path.read_text(encoding="utf-8"))
    return {
        p["codigo"]: Rate(
            p["codigo"],
            p["unidad"],
            Decimal(p["precio_unitario_eur"]),
            Decimal(p["rendimiento_por_dia"]),
            p["actividad"],
        )
        for p in data["partidas"]
    }


def budget_lines(items: list[dict], rates: dict[str, Rate]) -> tuple[list[dict], Decimal]:
    lines, total = [], Decimal(0)
    for it in items:
        rate = rates[
            it["code"]
        ]  # KeyError means no rate: the takeoff already rejects unknown items
        amount = (Decimal(it["quantity"]) * rate.unit_price).quantize(
            Decimal("0.01"), ROUND_HALF_UP
        )
        lines.append({"code": it["code"], "quantity": it["quantity"]})
        total += amount
    return lines, total.quantize(Decimal("0.01"))


@dataclass
class RiskSignal:
    kind: str
    severity: str
    description: str
    citations: list[Citation]


def risk_signals(
    fields: dict[str, str], citations: dict[str, Citation], injections: list[Citation]
) -> list[RiskSignal]:
    signals: list[RiskSignal] = []
    a, b = fields.get("plazo_ejecucion_meses"), fields.get("anexo_plazo_ejecucion_meses")
    if a and b and a != b:
        signals.append(
            RiskSignal(
                "CONTRADICTION",
                "HIGH",
                f"execution term differs: clauses {a} months, annex {b} months",
                [citations["plazo_ejecucion_meses"], citations["anexo_plazo_ejecucion_meses"]],
            )
        )
    pen = fields.get("penalidad_demora_eur_por_mil_dia")
    if pen and Decimal(pen) > COMPANY["max_penalty_eur_per_mil_day"]:
        signals.append(
            RiskSignal(
                "DEMANDING_CLAUSE",
                "MEDIUM",
                f"delay penalty {pen} EUR per 1,000 EUR per day",
                [citations["penalidad_demora_eur_por_mil_dia"]],
            )
        )
    sub = fields.get("subcontratacion_max_pct")
    if sub and int(sub) < COMPANY["min_subcontracting_pct"]:
        signals.append(
            RiskSignal(
                "DEMANDING_CLAUSE",
                "MEDIUM",
                f"subcontracting limited to {sub} %",
                [citations["subcontratacion_max_pct"]],
            )
        )
    for c in injections:
        signals.append(
            RiskSignal(
                "INJECTION",
                "HIGH",
                "document contains an instruction addressed to an automated system",
                [c],
            )
        )
    return signals


@dataclass
class BidDecision:
    recommendation: str  # BID | NO_BID | REVIEW
    reasons: list[str]
    margin_pct: str | None


def bid_no_bid(
    fields: dict[str, str], cost: Decimal | None, signals: list[RiskSignal]
) -> BidDecision:
    """Explicit rules. Any planted instruction or contradiction sends the decision to REVIEW."""
    reasons: list[str] = []
    no_bid = False
    margin = None
    base = fields.get("presupuesto_base_eur")
    if base and cost:
        margin = ((Decimal(base) - cost) / Decimal(base) * 100).quantize(Decimal("0.1"))
        if margin < COMPANY["min_margin_pct"]:
            no_bid = True
            reasons.append(f"margin {margin} % below {COMPANY['min_margin_pct']} %")
    else:
        reasons.append("budget or base price missing")
    eco = fields.get("solvencia_economica_eur")
    if eco and Decimal(eco) > COMPANY["annual_turnover_eur"]:
        no_bid = True
        reasons.append("economic solvency above company turnover")
    tec = fields.get("solvencia_tecnica_obras")
    if tec and int(tec) > COMPANY["similar_works_last_5_years"]:
        no_bid = True
        reasons.append("technical solvency above company experience")
    review = [s for s in signals if s.kind in ("CONTRADICTION", "INJECTION")]
    if review:
        reasons += [f"{s.kind.lower()} needs a person" for s in review]
        return BidDecision("REVIEW", reasons, None if margin is None else str(margin))
    return BidDecision(
        "NO_BID" if no_bid else "BID", reasons, None if margin is None else str(margin)
    )
