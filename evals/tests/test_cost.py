from __future__ import annotations

from pathlib import Path

from cimiento_evals.cost import check_no_content, report

PRICES = {
    "eur_per_usd": None,
    "models": {"claude-opus-5-5": {"input_usd_per_mtok": 4.0, "output_usd_per_mtok": 20.0}},
}
DATA = Path(__file__).resolve().parents[2] / "data" / "generated"


def chat(provider, model, inp, out):
    return {
        "name": f"chat {model}",
        "attributes": {
            "gen_ai.operation.name": "chat",
            "gen_ai.provider.name": provider,
            "gen_ai.request.model": model,
            "gen_ai.usage.input_tokens": inp,
            "gen_ai.usage.output_tokens": out,
        },
    }


def test_cost_only_for_priced_models():
    spans = [
        chat("anthropic", "claude-opus-5-5", 1_000_000, 100_000),
        chat("anthropic", "claude-opus-5-5", 500_000, 0),
        chat("anthropic", "claude-unknown", 10, 10),
        chat("cimiento.rule-based", "rule-based", 0, 0),
    ]
    rows = {r["model"]: r for r in report(spans, PRICES)["rows"]}
    opus = rows["claude-opus-5-5"]
    # 1.5 M input x 4 + 0.1 M output x 20 = 6 + 2 = 8 USD
    assert (opus["calls"], opus["usd"], opus["eur"]) == (2, "8.0000", None)
    assert rows["claude-unknown"]["usd"] is None and rows["rule-based"]["usd"] is None
    eur = {r["model"]: r for r in report(spans, {**PRICES, "eur_per_usd": 0.9})["rows"]}
    assert eur["claude-opus-5-5"]["eur"] == "7.2000"


def test_content_check_catches_document_text():
    page = next((DATA / "dev" / "tenders" / "D-001").glob("pliego.txt")).read_text(encoding="utf-8")
    sentence = next(
        line for line in page.splitlines() if len(line) > 40 and not line.startswith("===")
    )
    leaked = [
        {"name": "x", "attributes": {"cimiento.note": sentence}},
        {"name": "y", "attributes": {"cimiento.code": "Constructora_Brisa_Plazo"}},
    ]
    result = check_no_content(leaked, DATA)
    assert "x:cimiento.note" in result["violations"]
    clean = check_no_content(
        [{"name": "z", "attributes": {"cimiento.tender.id": "D-001", "n": 3}}], DATA
    )
    assert clean["violations"] == []
