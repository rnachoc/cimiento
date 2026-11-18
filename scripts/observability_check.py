"""Send the telemetry of a few tenders through OTLP and check what reaches Prometheus, Jaeger and
Grafana (run by scripts/observability.sh)."""

from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

import httpx
from opentelemetry import metrics, trace

from cimiento_agents import tracing
from cimiento_agents.coordinator import analyse_tender
from cimiento_agents.core_client import RecordingCore
from cimiento_agents.llm import FakeModel
from cimiento_agents.tender_agents import load_rates

PROM, JAEGER, GRAFANA = os.environ["PROM_URL"], os.environ["JAEGER_URL"], os.environ["GRAFANA_URL"]
DASHBOARD = Path(os.environ["DASHBOARD_JSON"])


def check(cond: bool, what: str) -> None:
    print(("ok   " if cond else "FAIL ") + what, flush=True)
    if not cond:
        sys.exit(1)


def main() -> None:
    tracing.configure()
    split = Path(os.environ["DATA_ROOT"]) / "eval"
    rates = load_rates(split / "company" / "tarifas.json")
    for t in ("E-001", "E-002", "E-003"):
        analyse_tender(split / "tenders" / t, FakeModel(None), RecordingCore(), "2027-01-04", rates)
    trace.get_tracer_provider().force_flush()  # type: ignore[attr-defined]
    metrics.get_meter_provider().force_flush()  # type: ignore[attr-defined]

    # every panel query of the dashboard returns data
    panels = json.loads(DASHBOARD.read_text(encoding="utf-8"))["panels"]
    for panel in panels:
        expr = panel["targets"][0]["expr"]
        result = []
        for _ in range(30):
            result = httpx.get(f"{PROM}/api/v1/query", params={"query": expr}).json()["data"]["result"]
            if result:
                break
            time.sleep(2)
        check(bool(result), f"panel '{panel['title']}' has data")

    services = []
    for _ in range(20):
        services = httpx.get(f"{JAEGER}/api/services").json().get("data") or []
        if "cimiento-agents" in services:
            break
        time.sleep(2)
    check("cimiento-agents" in services, "Jaeger received traces from cimiento-agents")
    traces = httpx.get(f"{JAEGER}/api/traces", params={"service": "cimiento-agents",
                                                        "operation": "cimiento.tender", "limit": 10}).json()["data"]
    check(len(traces) >= 3, f"one trace per tender in Jaeger ({len(traces)})")
    ops = {s["operationName"] for s in traces[0]["spans"]}
    check({"agent.bid_analyst", "agent.planner", "chat rule-based", "POST /api/proposals"} <= ops,
          "a tender trace holds agent, model and core spans")

    found = httpx.get(f"{GRAFANA}/api/search", params={"query": "Cimiento"}).json()
    check(any(d.get("uid") == "cimiento-agents" for d in found), "Grafana provisioned the Cimiento dashboard")
    print("observability ok")


if __name__ == "__main__":
    main()
