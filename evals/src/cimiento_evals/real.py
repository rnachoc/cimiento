"""Real-model run through the Message Batches API, one model at a time, eval split only.

    uv run cimiento-evals real --model claude-haiku-5-5 [--confirm]

Without --confirm it only counts the requests it would send. With --confirm it submits the
batches (stage 1: bid analysis and takeoff; stage 2: planning, which needs the real takeoff),
waits, saves every answer as a recording, then evaluates with answers taken only from those
recordings. A failed answer counts as a model failure.
"""

from __future__ import annotations

import json
import time
from decimal import Decimal
from pathlib import Path
from typing import Any

from cimiento_agents.batch import CollectingModel, StrictReplayModel, collect, submit, wait
from cimiento_agents.bid_analyst import BidExtraction
from cimiento_agents.coordinator import analyse_tender
from cimiento_agents.core_client import RecordingCore
from cimiento_agents.llm import Recordings
from cimiento_agents.planner import PlanChoice
from cimiento_agents.takeoff import TakeoffReading
from cimiento_agents.tender_agents import load_rates

STAGES: list[set[str]] = [{"bid_analysis", "takeoff"}, {"planning"}]
SCHEMAS = {"bid_analysis": BidExtraction, "takeoff": TakeoffReading, "planning": PlanChoice}
BATCH_DISCOUNT = Decimal("0.5")


def gather(
    model: str, split_dir: Path, recordings: Recordings, allowed: set[str]
) -> CollectingModel:
    collector = CollectingModel(model, recordings, allowed_tasks=allowed)
    rates = load_rates(split_dir / "company" / "tarifas.json")
    gt = split_dir / "ground_truth"
    for folder in sorted((split_dir / "tenders").iterdir()):
        start = json.loads((gt / f"{folder.name}.json").read_text(encoding="utf-8"))["planning"][
            "fecha_inicio"
        ]
        analyse_tender(folder, collector, RecordingCore(), start, rates)
    return collector


def tokens_from_recordings(recordings_dir: Path, model: str) -> dict[str, int]:
    """Every answer recorded for this model (all batches, including reruns)."""
    keys = (
        "input_tokens",
        "output_tokens",
        "cache_read_input_tokens",
        "cache_creation_input_tokens",
    )
    totals = dict.fromkeys(keys, 0) | {"answers": 0, "failed": 0}
    for path in recordings_dir.glob(f"*/{model}/*.json"):
        rec = json.loads(path.read_text(encoding="utf-8"))
        for k in keys:
            totals[k] += int(rec.get(k, 0))
        totals["answers"] += 1
        totals["failed"] += rec.get("output") is None
    return totals


def cost_usd(stats: dict[str, int], prices: dict, model: str) -> str | None:
    price = prices["models"].get(model)
    if not price:
        return None
    inp = Decimal(str(price["input_usd_per_mtok"]))
    out = Decimal(str(price["output_usd_per_mtok"]))
    total = (
        (
            Decimal(stats["input_tokens"]) * inp
            + Decimal(stats["cache_creation_input_tokens"]) * inp * Decimal("1.25")
            + Decimal(stats["cache_read_input_tokens"]) * inp * Decimal("0.1")
            + Decimal(stats["output_tokens"]) * out
        )
        / Decimal(1_000_000)
        * BATCH_DISCOUNT
    )
    return str(total.quantize(Decimal("0.0001")))


def run(
    model: str,
    data_dir: Path,
    recordings_dir: Path,
    out_dir: Path,
    prices: dict,
    confirm: bool,
    client: Any = None,
    poll_seconds: float = 30.0,
) -> dict:
    split_dir = data_dir / "eval"
    recordings = Recordings(recordings_dir)
    totals = {
        "succeeded": 0,
        "failed": 0,
        "input_tokens": 0,
        "output_tokens": 0,
        "cache_read_input_tokens": 0,
        "cache_creation_input_tokens": 0,
    }
    batches: list[str] = []
    for stage in STAGES:
        collector = gather(model, split_dir, recordings, stage)
        n = len(collector.pending)
        print(f"{model}: stage {sorted(stage)}: {n} requests to send", flush=True)
        if n == 0:
            continue
        if not confirm:
            print("dry run: nothing sent (use --confirm)")
            return {"model": model, "dry_run": True, "requests": n}
        if client is None:
            import anthropic

            client = anthropic.Anthropic()
        batch_id = submit(client, collector.pending)
        batches.append(batch_id)
        wait(client, batch_id, poll_seconds)
        stats = collect(client, batch_id, collector.pending, model, recordings, SCHEMAS)
        for k in totals:
            totals[k] += stats[k]
        print(f"{model}: {stats}", flush=True)

    # evaluation with answers only from the recordings
    from .bid import run as run_bid
    from .plan import run as run_plan
    from .site import run as run_site

    replay = StrictReplayModel(model, recordings)
    bid, plan, site = (
        run_bid(split_dir, replay),
        run_plan(split_dir, replay),
        run_site(split_dir, replay),
    )
    recorded = tokens_from_recordings(recordings_dir, model)
    summary = {
        "model": model,
        "date": time.strftime("%Y-%m-%d"),
        "batches": batches,
        "tokens_this_run": totals,
        "tokens": recorded,
        "cost_usd_batch": cost_usd(recorded, prices, model),
        "model_failures": replay.failures,
        "bid": bid,
        "plan": plan,
        "site": site,
    }
    target = out_dir / "real" / model
    target.mkdir(parents=True, exist_ok=True)
    (target / "summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    return summary
