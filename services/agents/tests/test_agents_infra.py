from __future__ import annotations

import builtins
import io
import json
import pathlib
from types import SimpleNamespace

import pytest

from cimiento_agents import telemetry
from cimiento_agents.bid_analyst import BidExtraction, rule_based_extract
from cimiento_agents.citations import Citation, TenderDocs
from cimiento_agents.coordinator import analyse_tender
from cimiento_agents.core_client import ForbiddenCall, RecordingCore, check_allowed
from cimiento_agents.llm import (
    ClaudeModel,
    FakeModel,
    ModelRefused,
    Recordings,
    request_key,
    strict_schema,
)
from cimiento_agents.numbers import parse_es_words

SRC = pathlib.Path(__file__).resolve().parents[1] / "src" / "cimiento_agents"


# ------------------------------------------------------------ telemetry


def test_telemetry_accepts_ids_counts_and_timings():
    telemetry.event("bid_analysis", tender="D-001", fields=12, ms=3.5, ok=True)
    assert telemetry.EVENTS[-1]["tender"] == "D-001"


@pytest.mark.parametrize(
    "value", ["El plazo de ejecución será de doce meses", "x" * 65, {"a": 1}, ["x"]]
)
def test_telemetry_rejects_content(value):
    with pytest.raises(telemetry.ContentInTelemetry):
        telemetry.event("bid_analysis", detail=value)


# ------------------------------------------------------------ core client allow list


@pytest.mark.parametrize(
    "method,path",
    [
        ("POST", "/api/proposals/1/decision"),
        ("POST", "/api/projects"),
        ("DELETE", "/api/proposals/1"),
        ("PUT", "/api/tenders"),
        ("POST", "/api/audit/verify"),
    ],
)
def test_agent_cannot_call_outside_allow_list(method, path):
    with pytest.raises(ForbiddenCall):
        check_allowed(method, path)


def test_allowed_calls():
    for method, path in [
        ("POST", "/api/tenders"),
        ("POST", "/api/proposals"),
        ("GET", "/api/proposals/3"),
    ]:
        check_allowed(method, path)


def test_core_client_never_sends_a_decision(monkeypatch):
    import httpx

    from cimiento_agents.core_client import CoreClient

    sent = []
    transport = httpx.MockTransport(lambda req: sent.append(req) or httpx.Response(200, json={}))
    client = CoreClient(
        "http://core", "k" * 20, httpx.Client(base_url="http://core", transport=transport)
    )
    with pytest.raises(ForbiddenCall):
        client._call("POST", "/api/proposals/1/decision", {"outcome": "APPROVE"})
    assert sent == []
    client.submit(1, "BID_ANALYSIS", {"a": 1}, [], [])
    assert sent[0].headers["Idempotency-Key"] and sent[0].headers["X-Api-Key"] == "k" * 20
    client.submit(1, "BID_ANALYSIS", {"a": 1}, [], [])
    assert sent[0].headers["Idempotency-Key"] == sent[1].headers["Idempotency-Key"]


# ------------------------------------------------------------ models


def test_number_words():
    assert parse_es_words("doce") == 12
    assert parse_es_words("treinta y un") == 31
    assert parse_es_words("veintiún") == 21
    assert parse_es_words("plazo") is None


def test_strict_schema_closes_every_object():
    s = strict_schema(BidExtraction)
    objects = [s] + list(s.get("$defs", {}).values())
    for o in objects:
        if "properties" in o:
            assert o["additionalProperties"] is False
            assert set(o["required"]) == set(o["properties"])


class FakeAnthropic:
    """Records the request and returns a canned structured answer."""

    def __init__(self, text: str, stop_reason: str = "end_turn"):
        self.calls: list[dict] = []
        self.text, self.stop_reason = text, stop_reason
        self.messages = SimpleNamespace(create=self._create)

    def _create(self, **params):
        self.calls.append(params)
        return SimpleNamespace(
            stop_reason=self.stop_reason,
            content=[SimpleNamespace(type="text", text=self.text)],
            usage=SimpleNamespace(input_tokens=1200, output_tokens=300),
        )


def test_claude_model_request_shape_and_recording(tmp_path):
    answer = BidExtraction(fields=[], not_found=["plazo_ejecucion_meses"], injections=[])
    fake = FakeAnthropic(answer.model_dump_json())
    rec = Recordings(tmp_path)
    m = ClaudeModel("claude-opus-5-5", recordings=rec, client=fake, record=True)
    res = m.run("bid_analysis", "SYSTEM", "USER", BidExtraction)
    assert res.source == "claude" and res.output == answer and res.input_tokens == 1200
    p = fake.calls[0]
    assert p["model"] == "claude-opus-5-5"
    assert p["system"][0]["cache_control"] == {"type": "ephemeral"}
    assert p["output_config"]["format"]["type"] == "json_schema"
    assert "temperature" not in p and "thinking" not in p
    # the recording replays without a key and is labelled with model and date
    replay = FakeModel(rec, "claude-opus-5-5").run("bid_analysis", "SYSTEM", "USER", BidExtraction)
    assert replay.source == "recorded" and replay.output == answer
    assert replay.model.startswith("claude-opus-5-5@")
    key = request_key("bid_analysis", "claude-opus-5-5", "SYSTEM", "USER", BidExtraction)
    assert rec.path("bid_analysis", "claude-opus-5-5", key).exists()


def test_claude_refusal_is_an_error_not_an_answer():
    fake = FakeAnthropic("{}", stop_reason="refusal")
    with pytest.raises(ModelRefused):
        ClaudeModel("claude-opus-5-5", client=fake).run("t", "s", "u", BidExtraction)


def test_fake_model_without_recording_uses_labelled_rule_reader():
    answer = BidExtraction(fields=[], not_found=[], injections=[])
    res = FakeModel(None).run("bid_analysis", "s", "u", BidExtraction, lambda: answer)
    assert res.source == "rule-based"
    with pytest.raises(LookupError):
        FakeModel(None).run("bid_analysis", "s", "u", BidExtraction)


# ------------------------------------------------------------ citations and coordinator


def test_citation_verification(dev_split):
    docs = TenderDocs.load(dev_split / "tenders" / "D-001")
    first_line = docs.pages["pliego"][0].splitlines()[0]
    assert docs.verify(Citation(document="pliego", page=1, text=first_line))
    assert not docs.verify(Citation(document="pliego", page=99, text=first_line))
    assert not docs.verify(Citation(document="pliego", page=1, text="texto inventado que no está"))
    assert not docs.verify(Citation(document="otro", page=1, text=first_line))


class LyingModel:
    """Returns a value with a fabricated citation and obeys nothing else."""

    def run(self, task, system, user, schema, rule_based=None):
        from cimiento_agents.llm import ModelResult

        out = rule_based()
        out.fields[0].citation = Citation(
            document="pliego", page=1, text="cita inventada por el modelo"
        )
        return ModelResult(out, "claude", "test-model")


def test_unverified_citations_are_dropped(dev_split):
    core = RecordingCore()
    run = analyse_tender(dev_split / "tenders" / "D-002", LyingModel(), core, "2027-01-04")
    assert run.dropped_unverified == 1
    assert (
        len(run.fields)
        == len(rule_based_extract(TenderDocs.load(dev_split / "tenders" / "D-002")).fields) - 1
    )


def test_coordinator_proposes_and_never_executes(dev_split):
    core = RecordingCore()
    tenders = sorted(p.name for p in (dev_split / "tenders").iterdir())
    for t in tenders[:4]:
        analyse_tender(dev_split / "tenders" / t, FakeModel(None), core, "2027-01-04")
    assert {p["type"] for p in core.proposals} == {"BID_ANALYSIS"}
    assert all(p["status"] == "PENDING_REVIEW" for p in core.proposals)
    assert all(p["citations"] for p in core.proposals)


def test_injection_is_reported_and_flagged(dev_split):
    # find a tender whose documents carry a planted instruction (detected from documents only)
    core = RecordingCore()
    flagged = []
    for folder in sorted((dev_split / "tenders").iterdir()):
        run = analyse_tender(folder, FakeModel(None), core, "2027-01-04")
        if run.injections:
            flagged.append(folder.name)
    assert flagged and all(not f.endswith("-gemelo") for f in flagged)
    assert sum("INJECTION_DETECTED" in p["signals"] for p in core.proposals) == len(flagged)


def test_agents_never_read_ground_truth(dev_split, monkeypatch):
    """Static and runtime check: no agent code touches ground_truth files."""
    for path in SRC.rglob("*.py"):
        text = path.read_text(encoding="utf-8")
        assert "ground_truth" not in text and "gemelo_de" not in text, path

    real_open = builtins.open

    def guarded_open(file, *args, **kwargs):
        if "ground_truth" in str(file):
            raise AssertionError(f"agents read ground truth: {file}")
        return real_open(file, *args, **kwargs)

    monkeypatch.setattr(builtins, "open", guarded_open)
    monkeypatch.setattr(io, "open", guarded_open)
    analyse_tender(dev_split / "tenders" / "D-003", FakeModel(None), RecordingCore(), "2027-01-04")
    json.dumps({})  # keep import used
