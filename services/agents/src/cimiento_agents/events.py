"""Kafka consumers with retries, a dead-letter queue and replay, plus the flow bridge.

Delivery is at least once: an offset is committed only after the message was handled or
parked in the dead-letter topic, so handlers must be idempotent. The bridge is: every
side effect it causes goes through the core with idempotency keys.

Topics
- cimiento.intake: a tender arrived (key: project). The bridge runs the agents on it.
- cimiento.audit: every audit entry of the core, published through its outbox (key: project).
- <topic>.dlq: messages that failed after all retries, with the error in the headers.
"""

from __future__ import annotations

import json
import re
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from . import telemetry

INTAKE = "cimiento.intake"
AUDIT = "cimiento.audit"


@dataclass
class Message:
    topic: str
    key: str | None
    value: bytes
    partition: int = 0
    offset: int = 0
    headers: dict[str, str] | None = None


class Producer(Protocol):
    def send(self, topic: str, key: str | None, value: bytes, headers: dict[str, str]) -> None: ...


@dataclass
class Outcome:
    handled: bool
    attempts: int
    dead_lettered: bool
    error: str | None = None


def handle_with_retries(
    msg: Message,
    handler: Callable[[Message], None],
    producer: Producer,
    retries: int = 3,
    base_delay: float = 0.5,
    sleep: Callable[[float], None] = time.sleep,
) -> Outcome:
    """Run the handler; on error wait base_delay * 2**attempt and retry; after `retries`
    failures publish the message to <topic>.dlq with the error in the headers."""
    error = None
    for attempt in range(retries):
        try:
            handler(msg)
            return Outcome(True, attempt + 1, False)
        except Exception as e:  # noqa: BLE001 - any handler error is retried, then dead-lettered
            error = f"{type(e).__name__}: {e}"[:300]
            telemetry.event("consumer_retry", topic=msg.topic, attempt=attempt + 1)
            if attempt < retries - 1:
                sleep(base_delay * 2**attempt)
    headers = {
        **(msg.headers or {}),
        "error": error or "",
        "original_topic": msg.topic,
        "original_partition": str(msg.partition),
        "original_offset": str(msg.offset),
        "attempts": str(retries),
    }
    producer.send(f"{msg.topic}.dlq", msg.key, msg.value, headers)
    telemetry.event("consumer_dead_letter", topic=msg.topic, attempts=retries)
    return Outcome(False, retries, True, error)


def replay(dlq_messages: list[Message], producer: Producer) -> int:
    """Send dead-lettered messages back to their original topic (same key and value)."""
    for m in dlq_messages:
        target = (m.headers or {}).get("original_topic") or m.topic.removesuffix(".dlq")
        producer.send(target, m.key, m.value, {"replayed_from": m.topic})
    return len(dlq_messages)


# ---------------------------------------------------------------- the flow bridge


class IntakeBridge:
    """cimiento.intake -> run the agents on the tender -> proposals in the core."""

    def __init__(self, data_root: Path, core: Any, model: Any) -> None:
        self.data_root = data_root
        self.core = core
        self.model = model

    def __call__(self, msg: Message) -> None:
        from .coordinator import analyse_tender
        from .tender_agents import load_rates

        event = json.loads(msg.value)
        split, tender, start = event["split"], event["tender"], event["planned_start"]
        if split not in ("dev", "eval"):
            raise ValueError("split must be dev or eval")
        if not re.fullmatch(r"[DE]-\d{3}(-gemelo)?", str(tender)):
            raise ValueError("invalid tender id")
        folder = self.data_root / split / "tenders" / tender
        if not folder.is_dir():
            raise FileNotFoundError(f"tender {tender} not found")
        rates = load_rates(self.data_root / split / "company" / "tarifas.json")
        analyse_tender(folder, self.model, self.core, start, rates)
        telemetry.event("intake_handled", tender=tender)


# ---------------------------------------------------------------- confluent-kafka adapters


class KafkaProducer:
    def __init__(self, bootstrap: str) -> None:
        from confluent_kafka import Producer as CProducer

        self._p = CProducer(
            {"bootstrap.servers": bootstrap, "enable.idempotence": True, "acks": "all"}
        )

    def send(self, topic: str, key: str | None, value: bytes, headers: dict[str, str]) -> None:
        self._p.produce(topic, key=key, value=value, headers=list(headers.items()))
        self._p.flush(10)


def consume(
    bootstrap: str,
    group: str,
    topic: str,
    handler: Callable[[Message], None],
    producer: Producer,
    max_messages: int | None = None,
    idle_timeout: float = 10.0,
    retries: int = 3,
    base_delay: float = 0.5,
) -> list[Outcome]:
    """Consume `topic` with manual commits; stops after max_messages or idle_timeout seconds
    without messages."""
    from confluent_kafka import Consumer

    c = Consumer(
        {
            "bootstrap.servers": bootstrap,
            "group.id": group,
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
        }
    )
    c.subscribe([topic])
    outcomes: list[Outcome] = []
    idle_since = time.monotonic()
    try:
        while max_messages is None or len(outcomes) < max_messages:
            raw = c.poll(0.5)
            if raw is None:
                if time.monotonic() - idle_since > idle_timeout:
                    break
                continue
            if raw.error():
                continue
            idle_since = time.monotonic()
            msg = Message(
                raw.topic(),
                raw.key().decode() if raw.key() else None,
                raw.value(),
                raw.partition(),
                raw.offset(),
                {k: (v.decode() if v else "") for k, v in (raw.headers() or [])},
            )
            outcomes.append(handle_with_retries(msg, handler, producer, retries, base_delay))
            c.commit(raw, asynchronous=False)  # after handling or dead-lettering: at least once
    finally:
        c.close()
    return outcomes


def read_all(bootstrap: str, topic: str, idle_timeout: float = 5.0) -> list[Message]:
    """Read a topic from the beginning with a throwaway group (used by tests and replay)."""
    import uuid

    from confluent_kafka import Consumer

    c = Consumer(
        {
            "bootstrap.servers": bootstrap,
            "group.id": f"reader-{uuid.uuid4()}",
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
        }
    )
    c.subscribe([topic])
    out: list[Message] = []
    idle_since = time.monotonic()
    try:
        while time.monotonic() - idle_since < idle_timeout:
            raw = c.poll(0.5)
            if raw is None or raw.error():
                continue
            idle_since = time.monotonic()
            out.append(
                Message(
                    raw.topic(),
                    raw.key().decode() if raw.key() else None,
                    raw.value(),
                    raw.partition(),
                    raw.offset(),
                    {k: (v.decode() if v else "") for k, v in (raw.headers() or [])},
                )
            )
    finally:
        c.close()
    return out
