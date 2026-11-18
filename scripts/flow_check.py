"""Full flow through Kafka: tender intake -> agents -> proposals -> reviewer decision.

Run by scripts/flow.sh against Postgres, Kafka and the core (outbox on) in docker compose.
Checks, with a real broker:
1. an intake event is consumed by the bridge, which runs the agents (proposals in the core);
2. the core's audit events arrive on cimiento.audit through the outbox, keyed by project,
   in order, without document text;
3. a reviewer decision ends the flow: DECISION_RECORDED and PROPOSAL_EXECUTED arrive;
4. a bad intake event goes to cimiento.intake.dlq after retries and can be replayed;
5. replaying a good intake event creates no duplicate proposals (idempotent consumers).
"""

from __future__ import annotations

import json
import os
import sys
import uuid

import httpx

from cimiento_agents.core_client import CoreClient
from cimiento_agents.events import (
    AUDIT, INTAKE, IntakeBridge, KafkaProducer, consume, read_all, replay,
)
from cimiento_agents.llm import FakeModel

BOOT = os.environ["KAFKA_BOOTSTRAP"]
CORE = os.environ["CORE_URL"]
DATA = os.path.abspath(os.environ.get("DATA_ROOT", "data/generated"))
PROJECT = "LICITACIONES"


def check(cond: bool, what: str) -> None:
    print(("ok   " if cond else "FAIL ") + what, flush=True)
    if not cond:
        sys.exit(1)


def main() -> None:
    from pathlib import Path

    reviewer = httpx.Client(base_url=CORE, headers={"X-Api-Key": os.environ["CIMIENTO_REVIEWER_KEY"]})
    analyst = httpx.Client(base_url=CORE, headers={"X-Api-Key": os.environ["CIMIENTO_ANALYST_KEY"]})
    reviewer.post("/api/projects", json={"code": PROJECT, "name": "Licitaciones"}).raise_for_status()

    producer = KafkaProducer(BOOT)
    core = CoreClient(CORE, os.environ["CIMIENTO_AGENT_KEY"])
    bridge = IntakeBridge(Path(DATA), core, FakeModel(None))
    group = f"bridge-{uuid.uuid4()}"

    # 1. intake -> bridge -> agents -> proposals
    good = json.dumps({"split": "dev", "tender": "D-011", "planned_start": "2027-01-04"}).encode()
    producer.send(INTAKE, PROJECT, good, {})
    out = consume(BOOT, group, INTAKE, bridge, producer, max_messages=1, idle_timeout=30)
    check(len(out) == 1 and out[0].handled, "bridge consumed the intake event and ran the agents")
    proposals = analyst.get("/api/proposals").json()
    check(len(proposals) >= 5, f"proposals created in the core: {len(proposals)}")

    # 2. audit events through the outbox
    bid = next(p for p in proposals if p["type"] == "BID_ANALYSIS")
    reviewer.post(f"/api/proposals/{bid['id']}/decision", json={"outcome": "APPROVE", "comment": "ok"}).raise_for_status()

    # 3. the decision arrives through Kafka
    events = []
    for _ in range(10):
        events = [json.loads(m.value) | {"_key": m.key} for m in read_all(BOOT, AUDIT, idle_timeout=4)]
        if any(e["action"] == "PROPOSAL_EXECUTED" and e["entityId"] == str(bid["id"]) for e in events):
            break
    actions = [e["action"] for e in events]
    check("TENDER_CREATED" in actions and actions.count("PROPOSAL_SUBMITTED") == len(proposals),
          f"audit events on {AUDIT}: {len(events)}")
    check(all(e["_key"] == PROJECT for e in events), "every event keyed by project")
    seqs = [e["seq"] for e in events]
    check(seqs == sorted(seqs), "events of the project in audit order")
    check(any(e["action"] == "DECISION_RECORDED" and e["entityId"] == str(bid["id"]) for e in events)
          and any(e["action"] == "PROPOSAL_EXECUTED" and e["entityId"] == str(bid["id"]) for e in events),
          "flow ends in a decision: DECISION_RECORDED and PROPOSAL_EXECUTED for the approved proposal")
    raw = json.dumps(events, ensure_ascii=False)
    check("Excavación" not in raw and "plazo de ejecución" not in raw.lower(), "no document text in events")

    # 4. a bad intake event is dead-lettered and can be replayed
    producer.send(INTAKE, PROJECT, json.dumps({"split": "dev", "tender": "D-999", "planned_start": "2027-01-04"}).encode(), {})
    out = consume(BOOT, group, INTAKE, bridge, producer, max_messages=1, idle_timeout=30, base_delay=0.2)
    check(out and out[0].dead_lettered and out[0].attempts == 3, "bad event retried 3 times then dead-lettered")
    dlq = read_all(BOOT, f"{INTAKE}.dlq")
    check(len(dlq) == 1 and "not found" in (dlq[0].headers or {}).get("error", ""), "dead letter carries the error")
    check(replay(dlq, producer) == 1, "replayed from the dead-letter topic")
    out = consume(BOOT, group, INTAKE, bridge, producer, max_messages=1, idle_timeout=30, base_delay=0.2)
    check(out and out[0].dead_lettered, "replayed event processed again (and fails again, reproducibly)")

    # 5. idempotency: the good intake again creates nothing new
    before = len(analyst.get("/api/proposals").json())
    producer.send(INTAKE, PROJECT, good, {})
    out = consume(BOOT, group, INTAKE, bridge, producer, max_messages=1, idle_timeout=30)
    after = len(analyst.get("/api/proposals").json())
    check(out[0].handled and after == before, f"redelivered intake is idempotent ({before} -> {after} proposals)")
    print("flow ok")


if __name__ == "__main__":
    main()
