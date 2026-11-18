"""Cost report from exported spans: tokens per model, converted to money only for models with a
configured price. The rule-based reader and recorded answers cost nothing and are listed as such.
Also checks that no span attribute carries document text."""

from __future__ import annotations

import json
import time
from collections import defaultdict
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

from cimiento_agents.telemetry import ContentInTelemetry, _check
from cimiento_agents.textnorm import normalize


def load_spans(path: Path) -> list[dict]:
    return [
        json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()
    ]


def check_no_content(spans: list[dict], data_dir: Path) -> dict:
    """Every attribute must be an identifier, count, timing or flag, and no string attribute
    longer than 12 characters may appear in any document page."""
    pages: list[str] = []
    for txt in data_dir.glob("*/tenders/*/*.txt"):
        pages.append(normalize(txt.read_text(encoding="utf-8")))
    corpus = "\n".join(pages)
    bad: list[str] = []
    checked = 0
    for s in spans:
        for k, v in s["attributes"].items():
            checked += 1
            try:
                _check(k.replace(".", "_"), v)
            except ContentInTelemetry:
                bad.append(f"{s['name']}:{k}")
                continue
            if isinstance(v, str) and len(v) > 12 and normalize(v) in corpus:
                bad.append(f"{s['name']}:{k}")
    return {"attributes_checked": checked, "violations": bad}


def report(spans: list[dict], prices: dict) -> dict:
    usage: dict[tuple[str, str], list[int]] = defaultdict(lambda: [0, 0, 0])
    for s in spans:
        a = s["attributes"]
        if a.get("gen_ai.operation.name") != "chat":
            continue
        key = (a.get("gen_ai.provider.name", "?"), a.get("gen_ai.request.model", "?"))
        usage[key][0] += int(a.get("gen_ai.usage.input_tokens", 0))
        usage[key][1] += int(a.get("gen_ai.usage.output_tokens", 0))
        usage[key][2] += 1
    rate = prices.get("eur_per_usd")
    rows = []
    for (provider, model), (inp, out, calls) in sorted(usage.items()):
        price = prices["models"].get(model) if provider == "anthropic" else None
        usd = eur = None
        if price:
            usd = (
                Decimal(inp) * Decimal(str(price["input_usd_per_mtok"]))
                + Decimal(out) * Decimal(str(price["output_usd_per_mtok"]))
            ) / Decimal(1_000_000)
            usd = usd.quantize(Decimal("0.0001"), ROUND_HALF_UP)
            if rate:
                eur = (usd * Decimal(str(rate))).quantize(Decimal("0.0001"), ROUND_HALF_UP)
        rows.append(
            {
                "provider": provider,
                "model": model,
                "calls": calls,
                "input_tokens": inp,
                "output_tokens": out,
                "usd": None if usd is None else str(usd),
                "eur": None if eur is None else str(eur),
            }
        )
    return {"date": time.strftime("%Y-%m-%d"), "eur_per_usd": rate, "rows": rows}


def render(cost: dict, content: dict) -> str:
    lines = [
        "# Cost report",
        "",
        f"Generated {cost['date']} by `make cost-report` from the spans of a `make eval-bid` run.",
        "",
        "| Provider | Model | Calls | Input tokens | Output tokens | USD | EUR |",
        "|---|---|---|---|---|---|---|",
    ]
    for r in cost["rows"]:
        usd = r["usd"] if r["usd"] is not None else "no price (not a billed model)"
        eur = (
            r["eur"]
            if r["eur"] is not None
            else ("set eur_per_usd" if r["usd"] is not None else "n/a")
        )
        lines.append(
            f"| {r['provider']} | {r['model']} | {r['calls']} | {r['input_tokens']} | "
            f"{r['output_tokens']} | {usd} | {eur} |"
        )
    lines += [
        "",
        "Prices: `evals/config/prices.json` (list prices, USD per million tokens). Euros are "
        "shown only when an exchange rate is set there.",
        "",
        f"Telemetry check: {content['attributes_checked']} span attributes checked, "
        f"{len(content['violations'])} with document text.",
        "",
    ]
    return "\n".join(lines)
