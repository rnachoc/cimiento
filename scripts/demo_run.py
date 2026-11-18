"""Demo step: run the agents on a few tenders against the running core and print a summary."""

from __future__ import annotations

import json
import os
from collections import Counter
from pathlib import Path

import httpx
from cimiento_agents.coordinator import PROJECT, analyse_tender
from cimiento_agents.core_client import CoreClient
from cimiento_agents.llm import Recordings, model_from_env
from cimiento_agents.tender_agents import load_rates

CORE = os.environ["CORE_URL"]
SPLIT = Path(os.environ["DATA_ROOT"]) / "eval"
# E-002 and E-012 hide planted instructions; the keyless reader detects the one in E-012 only.
TENDERS = ["E-001", "E-002", "E-003", "E-004", "E-005", "E-012"]


def main() -> None:
    reviewer = httpx.Client(
        base_url=CORE, headers={"X-Api-Key": os.environ["CIMIENTO_REVIEWER_KEY"]}
    )
    reviewer.post(
        "/api/projects", json={"code": PROJECT, "name": "Licitaciones"}
    ).raise_for_status()
    core = CoreClient(CORE, os.environ["CIMIENTO_AGENT_KEY"])
    recordings = Path(__file__).resolve().parents[1] / "evals" / "recordings"
    model = model_from_env(Recordings(recordings) if recordings.exists() else None)
    rates = load_rates(SPLIT / "company" / "tarifas.json")
    for t in TENDERS:
        start = json.loads((SPLIT / "ground_truth" / f"{t}.json").read_text(encoding="utf-8"))[
            "planning"
        ]["fecha_inicio"]
        run = analyse_tender(SPLIT / "tenders" / t, model, core, start, rates)
        print(
            f"  {t}: {len(run.fields)} fields with verified citations, {len(run.proposal_ids)} proposals, "
            f"recommendation {run.recommendation}, source {run.source}"
            + (", planted instruction detected" if run.injections else "")
        )
    analyst = httpx.Client(base_url=CORE, headers={"X-Api-Key": os.environ["CIMIENTO_ANALYST_KEY"]})
    proposals = analyst.get("/api/proposals").json()
    print("  proposals by status:", dict(Counter(p["status"] for p in proposals)))
    print("  audit chain:", analyst.get("/api/audit/verify").json()["message"])


if __name__ == "__main__":
    main()
