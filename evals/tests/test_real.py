"""Batch run plumbing with a simulated Batches client: no network, no cost."""

from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from cimiento_agents.bid_analyst import rule_based_extract, user_prompt
from cimiento_agents.citations import TenderDocs
from cimiento_agents.planner import PlanChoice
from cimiento_agents.takeoff import rule_based_read

from cimiento_evals.real import run

DATA = Path(__file__).resolve().parents[2] / "data" / "generated"
PRICES = {"models": {"claude-haiku-5-5": {"input_usd_per_mtok": 0.10, "output_usd_per_mtok": 0.50}}}


class FakeBatches:
    """Answers like a careful reader, except one request that errors and one refusal."""

    def __init__(self) -> None:
        self.answers: dict[str, str] = {}
        for folder in sorted((DATA / "eval" / "tenders").iterdir()):
            docs = TenderDocs.load(folder)
            prompt = user_prompt(docs)
            self.answers[f"bid_analysis|{prompt}"] = rule_based_extract(docs).model_dump_json()
            self.answers[f"takeoff|{prompt}"] = rule_based_read(docs).model_dump_json()
        self.batches: dict[str, list] = {}
        self.sent = 0

    def create(self, requests):
        bid = f"batch_{len(self.batches)}"
        self.batches[bid] = list(requests)
        self.sent += len(requests)
        return SimpleNamespace(id=bid)

    def retrieve(self, batch_id):
        return SimpleNamespace(
            processing_status="ended",
            request_counts=SimpleNamespace(
                processing=0, succeeded=len(self.batches[batch_id]), errored=0
            ),
        )

    def results(self, batch_id):
        for i, req in enumerate(self.batches[batch_id]):
            cid, params = req["custom_id"], req["params"]
            task = cid.split("-")[0]
            usage = SimpleNamespace(
                input_tokens=1000,
                output_tokens=100,
                cache_read_input_tokens=0,
                cache_creation_input_tokens=0,
            )
            if batch_id == "batch_0" and i == 0:
                yield SimpleNamespace(custom_id=cid, result=SimpleNamespace(type="errored"))
                continue
            if batch_id == "batch_0" and i == 1:
                msg = SimpleNamespace(stop_reason="refusal", content=[], usage=usage)
                yield SimpleNamespace(
                    custom_id=cid, result=SimpleNamespace(type="succeeded", message=msg)
                )
                continue
            if task == "planning":
                text = PlanChoice(crews=1, links=[]).model_dump_json()
            else:
                text = self.answers[f"{task}|{params['messages'][0]['content']}"]
            msg = SimpleNamespace(
                stop_reason="end_turn",
                content=[SimpleNamespace(type="text", text=text)],
                usage=usage,
            )
            yield SimpleNamespace(
                custom_id=cid, result=SimpleNamespace(type="succeeded", message=msg)
            )


@pytest.fixture(scope="module")
def outcome(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("real")
    fake = FakeBatches()
    client = SimpleNamespace(messages=SimpleNamespace(batches=fake))
    dry = run("claude-haiku-5-5", DATA, tmp / "rec", tmp / "out", PRICES, confirm=False)
    result = run(
        "claude-haiku-5-5",
        DATA,
        tmp / "rec",
        tmp / "out",
        PRICES,
        confirm=True,
        client=client,
        poll_seconds=0,
    )
    again = run(
        "claude-haiku-5-5",
        DATA,
        tmp / "rec",
        tmp / "out",
        PRICES,
        confirm=True,
        client=client,
        poll_seconds=0,
    )
    return dry, result, again, fake, tmp


def test_dry_run_sends_nothing(outcome):
    dry, *_ = outcome
    assert dry == {
        "model": "claude-haiku-5-5",
        "dry_run": True,
        "requests": 66,
    }  # 33 tenders x 2 tasks


def test_two_stages_and_failures_are_counted_not_filled_in(outcome):
    _, result, _, fake, tmp = outcome
    assert result["batches"] == ["batch_0", "batch_1"]
    assert result["tokens"]["failed"] == 2  # one error, one refusal
    assert result["model_failures"] >= 2
    assert result["bid"]["model_failures"] >= 1
    assert result["bid"]["sources"] == ["claude", "failed"]  # never "rule-based"
    assert (tmp / "out" / "real" / "claude-haiku-5-5" / "summary.json").exists()
    # cost from recorded tokens at half price; the errored request has no usage and is not billed
    billed = result["tokens"]["answers"] - 1
    assert result["tokens"]["input_tokens"] == billed * 1000
    assert result["cost_usd_batch"] == f"{billed * 150 / 1_000_000 / 2:.4f}"


def test_rerun_reuses_recordings_and_sends_nothing_new(outcome):
    _, result, again, fake, _ = outcome
    assert again["batches"] == []
    assert fake.sent == result["tokens"]["answers"]
    assert again["bid"]["fields"] == result["bid"]["fields"]
    json.dumps(again)  # serialisable
