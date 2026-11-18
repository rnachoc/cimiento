from __future__ import annotations

from collections import Counter
from pathlib import Path

import pytest
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from cimiento_agents import tracing
from cimiento_agents.citations import TenderDocs
from cimiento_agents.coordinator import analyse_tender
from cimiento_agents.core_client import RecordingCore
from cimiento_agents.injection import detect
from cimiento_agents.llm import ClaudeModel, FakeModel
from cimiento_agents.telemetry import ContentInTelemetry, _check
from cimiento_agents.tender_agents import load_rates
from cimiento_agents.textnorm import normalize

EXPORTER = InMemorySpanExporter()
tracing.configure(EXPORTER)


@pytest.fixture()
def spans():
    EXPORTER.clear()
    yield EXPORTER
    EXPORTER.clear()


def eval_split(dev_split: Path) -> Path:
    return dev_split.parent / "eval"


def test_one_trace_per_tender_with_agent_model_and_core_spans(dev_split, spans):
    split = eval_split(dev_split)
    rates = load_rates(split / "company" / "tarifas.json")
    tenders = ["E-001", "E-002"]
    for t in tenders:
        analyse_tender(split / "tenders" / t, FakeModel(None), RecordingCore(), "2027-01-04", rates)
    finished = spans.get_finished_spans()
    roots = [s for s in finished if s.name == "cimiento.tender"]
    assert sorted(s.attributes["cimiento.tender.id"] for s in roots) == tenders
    assert len({s.context.trace_id for s in roots}) == 2
    for root in roots:
        trace_spans = [s for s in finished if s.context.trace_id == root.context.trace_id]
        names = Counter(s.name for s in trace_spans)
        for agent in (
            "agent.bid_analyst",
            "agent.takeoff_analyst",
            "agent.planner",
            "agent.cost_estimator",
            "agent.risk_analyst",
            "agent.bid_no_bid",
            "agent.proposal_writer",
            "agent.progress_analyst",
            "agent.report_writer",
        ):
            assert names[agent] >= 1, agent
        chats = [s for s in trace_spans if s.name.startswith("chat ")]
        assert {s.attributes["cimiento.task"] for s in chats} >= {
            "bid_analysis",
            "takeoff",
            "planning",
        }
        assert all(s.attributes["gen_ai.operation.name"] == "chat" for s in chats)
        assert all("gen_ai.usage.input_tokens" in s.attributes for s in chats)
        assert names["POST /api/proposals"] == root.attributes["cimiento.proposals"]


def test_spans_never_carry_document_text(dev_split, spans):
    """Process every eval tender with planted instructions and check every attribute."""
    split = eval_split(dev_split)
    rates = load_rates(split / "company" / "tarifas.json")
    folders = sorted((split / "tenders").iterdir())
    planted = []
    for folder in folders:
        docs = TenderDocs.load(folder)
        planted += [normalize(c.text) for c in detect(docs)]
        analyse_tender(folder, FakeModel(None), RecordingCore(), "2027-01-04", rates)
    assert planted, "the eval split has planted instructions to look for"
    values = [(k, v) for s in spans.get_finished_spans() for k, v in (s.attributes or {}).items()]
    assert len(values) > 1000
    for k, v in values:
        _check(k.replace(".", "_"), v)  # identifiers, counts, timings, flags only
        if isinstance(v, str):
            assert " " not in v
            assert not any(v.lower() in p for p in planted if len(v) > 12)


def test_content_is_rejected_before_it_reaches_a_span():
    with pytest.raises(ContentInTelemetry):
        with tracing.span("x", **{"cimiento.note": "El plazo de ejecución será de doce meses"}):
            pass


def test_claude_spans_follow_genai_conventions(spans):
    from types import SimpleNamespace

    from cimiento_agents.bid_analyst import BidExtraction

    text = BidExtraction(fields=[], not_found=[], injections=[]).model_dump_json()
    fake = SimpleNamespace(
        messages=SimpleNamespace(
            create=lambda **p: SimpleNamespace(
                stop_reason="end_turn",
                content=[SimpleNamespace(type="text", text=text)],
                usage=SimpleNamespace(input_tokens=1500, output_tokens=200),
            )
        )
    )
    ClaudeModel("claude-opus-5-5", client=fake).run("bid_analysis", "SYSTEM", "USER", BidExtraction)
    (chat,) = [s for s in spans.get_finished_spans() if s.name == "chat claude-opus-5-5"]
    a = chat.attributes
    assert (a["gen_ai.provider.name"], a["gen_ai.request.model"]) == (
        "anthropic",
        "claude-opus-5-5",
    )
    assert (a["gen_ai.usage.input_tokens"], a["gen_ai.usage.output_tokens"]) == (1500, 200)
