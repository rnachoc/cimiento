"""Running the agents on Claude through the Message Batches API (half price, asynchronous).

Rounds: the coordinator runs with a CollectingModel that answers from recordings when they
exist and otherwise queues the request (returning the rule-based answer only so the run can
continue). Queued requests go out as one batch; results are saved as recordings. Tasks that
depend on earlier answers (planning needs the takeoff) get their real request in the next
round. The final evaluation uses StrictReplayModel: answers come only from the recordings, and
a missing or failed answer is a model failure, never filled in by the rule-based reader.
"""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any, TypeVar

from pydantic import BaseModel, ValidationError

from . import tracing
from .llm import ModelResult, Recordings, build_params, request_key

T = TypeVar("T", bound=BaseModel)


class ModelFailed(RuntimeError):
    """The model gave no usable answer for this request (error, refusal, invalid output)."""


@dataclass
class Pending:
    custom_id: str
    task: str
    key: str
    params: dict[str, Any]


@dataclass
class CollectingModel:
    model: str
    recordings: Recordings
    effort: str = "medium"
    # Only these tasks are queued this round: a task whose prompt depends on an earlier answer
    # waits for the round after that answer exists (planning needs the real takeoff).
    allowed_tasks: set[str] | None = None
    pending: dict[str, Pending] = field(default_factory=dict)

    def run(
        self,
        task: str,
        system: str,
        user: str,
        schema: type[T],
        rule_based: Callable[[], T] | None = None,
    ) -> ModelResult:
        key = request_key(task, self.model, system, user, schema)
        rec = self.recordings.load(task, self.model, key)
        if rec is not None and rec.get("output") is not None:
            return ModelResult(schema.model_validate(rec["output"]), "recorded", self.model)
        if rec is None and (self.allowed_tasks is None or task in self.allowed_tasks):
            cid = f"{task}-{key[:48]}"  # custom_id: at most 64 chars of [A-Za-z0-9_-]
            self.pending[cid] = Pending(
                cid, task, key, build_params(self.model, self.effort, system, user, schema)
            )
        if rule_based is None:
            raise ModelFailed(f"no answer yet for {task}")
        return ModelResult(rule_based(), "rule-based", "placeholder")


@dataclass
class StrictReplayModel:
    """Answers only from recordings; a missing or failed answer raises ModelFailed."""

    model: str
    recordings: Recordings
    failures: int = 0

    def run(
        self,
        task: str,
        system: str,
        user: str,
        schema: type[T],
        rule_based: Callable[[], T] | None = None,
    ) -> ModelResult:
        key = request_key(task, self.model, system, user, schema)
        rec = self.recordings.load(task, self.model, key)
        inp = int((rec or {}).get("input_tokens", 0))
        out = int((rec or {}).get("output_tokens", 0))
        with tracing.span(
            f"chat {self.model}",
            **{
                "gen_ai.operation.name": "chat",
                "gen_ai.provider.name": "anthropic.batch",
                "gen_ai.request.model": self.model,
                "cimiento.task": task,
                "gen_ai.usage.input_tokens": inp,
                "gen_ai.usage.output_tokens": out,
            },
        ):
            pass
        if rec is None or rec.get("output") is None:
            self.failures += 1
            raise ModelFailed(
                f"{self.model} gave no usable answer for {task}: "
                f"{(rec or {}).get('error', 'missing')}"
            )
        return ModelResult(
            schema.model_validate(rec["output"]), "claude", f"{self.model}@{rec['date']} (batch)"
        )


def submit(client: Any, pending: dict[str, Pending]) -> str:
    from anthropic.types.message_create_params import MessageCreateParamsNonStreaming
    from anthropic.types.messages.batch_create_params import Request

    batch = client.messages.batches.create(
        requests=[
            Request(custom_id=p.custom_id, params=MessageCreateParamsNonStreaming(**p.params))
            for p in pending.values()
        ]
    )
    return batch.id


def wait(
    client: Any,
    batch_id: str,
    poll_seconds: float = 30.0,
    log: Callable[[str], None] = print,
    sleep: Callable[[float], None] = time.sleep,
) -> Any:
    while True:
        batch = client.messages.batches.retrieve(batch_id)
        c = batch.request_counts
        log(
            f"batch {batch_id}: {batch.processing_status} (processing {c.processing}, "
            f"succeeded {c.succeeded}, errored {c.errored})"
        )
        if batch.processing_status == "ended":
            return batch
        sleep(poll_seconds)


def collect(
    client: Any,
    batch_id: str,
    pending: dict[str, Pending],
    model: str,
    recordings: Recordings,
    schemas: dict[str, type[BaseModel]],
) -> dict[str, int]:
    """Save every result as a recording (output or error). Returns counts and token totals."""
    stats = {
        "succeeded": 0,
        "failed": 0,
        "input_tokens": 0,
        "output_tokens": 0,
        "cache_read_input_tokens": 0,
        "cache_creation_input_tokens": 0,
    }
    today = time.strftime("%Y-%m-%d")
    for result in client.messages.batches.results(batch_id):
        p = pending.get(result.custom_id)
        if p is None:
            continue
        record: dict[str, Any] = {
            "model": model,
            "date": today,
            "task": p.task,
            "batch_id": batch_id,
            "output": None,
            "input_tokens": 0,
            "output_tokens": 0,
        }
        if result.result.type == "succeeded":
            msg = result.result.message
            u = msg.usage
            record["input_tokens"], record["output_tokens"] = u.input_tokens, u.output_tokens
            stats["input_tokens"] += u.input_tokens
            stats["output_tokens"] += u.output_tokens
            record["cache_read_input_tokens"] = getattr(u, "cache_read_input_tokens", 0) or 0
            record["cache_creation_input_tokens"] = (
                getattr(u, "cache_creation_input_tokens", 0) or 0
            )
            stats["cache_read_input_tokens"] += record["cache_read_input_tokens"]
            stats["cache_creation_input_tokens"] += record["cache_creation_input_tokens"]
            if msg.stop_reason == "refusal":
                record["error"] = "refusal"
            else:
                text = next((b.text for b in msg.content if b.type == "text"), "")
                try:
                    record["output"] = (
                        schemas[p.task].model_validate_json(text).model_dump(mode="json")
                    )
                except ValidationError as e:
                    record["error"] = f"invalid output: {e.error_count()} validation errors"
                    record["stop_reason"] = msg.stop_reason
        else:
            record["error"] = f"batch result {result.result.type}"
        stats["succeeded" if record["output"] is not None else "failed"] += 1
        recordings.save(p.task, model, p.key, record)
    return stats
