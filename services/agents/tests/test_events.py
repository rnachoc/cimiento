from __future__ import annotations

import json

import pytest

from cimiento_agents.core_client import RecordingCore
from cimiento_agents.events import IntakeBridge, Message, handle_with_retries, replay
from cimiento_agents.llm import FakeModel


class ListProducer:
    def __init__(self):
        self.sent: list[tuple[str, str | None, bytes, dict]] = []

    def send(self, topic, key, value, headers):
        self.sent.append((topic, key, value, headers))


def test_success_on_first_try():
    p = ListProducer()
    out = handle_with_retries(Message("t", "k", b"x"), lambda m: None, p, sleep=lambda s: None)
    assert (out.handled, out.attempts, out.dead_lettered) == (True, 1, False)
    assert p.sent == []


def test_retries_with_growing_backoff_then_succeeds():
    calls, waits = [], []

    def flaky(m):
        calls.append(1)
        if len(calls) < 3:
            raise RuntimeError("temporary")

    out = handle_with_retries(
        Message("t", "k", b"x"),
        flaky,
        ListProducer(),
        retries=3,
        base_delay=0.5,
        sleep=waits.append,
    )
    assert out.handled and out.attempts == 3
    assert waits == [0.5, 1.0]


def test_failure_goes_to_dead_letter_queue_with_context_and_can_be_replayed():
    p = ListProducer()
    waits: list[float] = []
    msg = Message("cimiento.intake", "P1", b'{"tender":"NOPE"}', partition=2, offset=41)
    out = handle_with_retries(
        msg,
        lambda m: (_ for _ in ()).throw(ValueError("bad tender")),
        p,
        retries=3,
        base_delay=0.1,
        sleep=waits.append,
    )
    assert out.dead_lettered and not out.handled and "bad tender" in out.error
    assert waits == [0.1, 0.2]
    topic, key, value, headers = p.sent[0]
    assert (topic, key, value) == ("cimiento.intake.dlq", "P1", b'{"tender":"NOPE"}')
    assert headers["original_topic"] == "cimiento.intake" and headers["original_offset"] == "41"
    assert "bad tender" in headers["error"]

    replayer = ListProducer()
    dlq = [Message(topic, key, value, headers=headers)]
    assert replay(dlq, replayer) == 1
    assert replayer.sent[0][:3] == ("cimiento.intake", "P1", b'{"tender":"NOPE"}')


def test_bridge_runs_the_agents_and_is_idempotent(dev_split):
    core = RecordingCore()
    bridge = IntakeBridge(dev_split.parent, core, FakeModel(None))
    event = json.dumps({"split": "dev", "tender": "D-002", "planned_start": "2027-01-04"}).encode()
    bridge(Message("cimiento.intake", "LICITACIONES", event))
    types = {p["type"] for p in core.proposals}
    assert {"BID_ANALYSIS", "TAKEOFF", "SCHEDULE", "BUDGET"} <= types
    assert list(core.tenders) == ["D-002"]


@pytest.mark.parametrize(
    "event",
    [
        {"split": "dev", "tender": "NOPE", "planned_start": "2027-01-04"},
        {"split": "../../etc", "tender": "D-001", "planned_start": "2027-01-04"},
        {"tender": "D-001"},
        {"split": "dev", "tender": "../../../etc/passwd", "planned_start": "2027-01-04"},
    ],
)
def test_bridge_rejects_bad_events(dev_split, event):
    bridge = IntakeBridge(dev_split.parent, RecordingCore(), FakeModel(None))
    with pytest.raises((ValueError, FileNotFoundError, KeyError)):
        bridge(Message("cimiento.intake", "P", json.dumps(event).encode()))
