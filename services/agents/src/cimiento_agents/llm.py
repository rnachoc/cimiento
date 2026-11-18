"""Model layer: Claude through the official SDK, recorded answers, or a rule-based reader.

Every result says where it came from (`source`): "claude", "recorded" or "rule-based".
Reports must keep these apart; rule-based numbers are the keyless baseline.

Structured output: the Pydantic schema is sent as `output_config.format` (JSON schema), and
the answer is validated with the same Pydantic model whatever the source.
"""

from __future__ import annotations

import hashlib
import json
import os
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal, Protocol, TypeVar

from pydantic import BaseModel

from . import telemetry

T = TypeVar("T", bound=BaseModel)
Source = Literal["claude", "recorded", "rule-based"]
DEFAULT_MODEL = "claude-opus-5-5"


@dataclass
class ModelResult:
    output: BaseModel
    source: Source
    model: str
    input_tokens: int = 0
    output_tokens: int = 0


class ModelRefused(RuntimeError):
    pass


class Model(Protocol):
    def run(
        self,
        task: str,
        system: str,
        user: str,
        schema: type[T],
        rule_based: Callable[[], T] | None = None,
    ) -> ModelResult: ...


def request_key(task: str, model: str, system: str, user: str, schema: type[BaseModel]) -> str:
    body = {
        "task": task,
        "model": model,
        "system": system,
        "user": user,
        "schema": schema.model_json_schema(),
    }
    return hashlib.sha256(json.dumps(body, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


class Recordings:
    """Recorded answers on disk: <dir>/<task>/<model>/<request key>.json."""

    def __init__(self, root: Path) -> None:
        self.root = root

    def path(self, task: str, model: str, key: str) -> Path:
        return self.root / task / model / f"{key}.json"

    def load(self, task: str, model: str, key: str) -> dict[str, Any] | None:
        p = self.path(task, model, key)
        return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None

    def save(self, task: str, model: str, key: str, record: dict[str, Any]) -> None:
        p = self.path(task, model, key)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(
            json.dumps(record, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )


class ClaudeModel:
    """Claude via the Anthropic SDK. Needs ANTHROPIC_API_KEY (or another SDK credential)."""

    def __init__(
        self,
        model: str = DEFAULT_MODEL,
        effort: str = "medium",
        recordings: Recordings | None = None,
        client: Any = None,
        record: bool | None = None,
    ) -> None:
        import anthropic

        self.model = model
        self.effort = effort
        self.client = client or anthropic.Anthropic()
        self.recordings = recordings
        self.record = (
            os.environ.get("CIMIENTO_RECORD", "false").lower() == "true"
            if record is None
            else record
        )

    def run(
        self,
        task: str,
        system: str,
        user: str,
        schema: type[T],
        rule_based: Callable[[], T] | None = None,
    ) -> ModelResult:
        params: dict[str, Any] = {
            "model": self.model,
            "max_tokens": 16000,
            # Stable instructions first and cached; the tender's text varies per request.
            "system": [{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
            "messages": [{"role": "user", "content": user}],
            "output_config": {
                "effort": self.effort,
                "format": {"type": "json_schema", "schema": strict_schema(schema)},
            },
        }
        start = time.perf_counter()
        response = self.client.messages.create(**params)
        if response.stop_reason == "refusal":
            telemetry.event("model_refusal", task=task, model=self.model)
            raise ModelRefused(f"{self.model} declined task {task}")
        text = next(b.text for b in response.content if b.type == "text")
        output = schema.model_validate_json(text)
        usage = response.usage
        telemetry.event(
            "model_call",
            task=task,
            model=self.model,
            source="claude",
            input_tokens=usage.input_tokens,
            output_tokens=usage.output_tokens,
            ms=round((time.perf_counter() - start) * 1000, 1),
        )
        if self.record and self.recordings is not None:
            key = request_key(task, self.model, system, user, schema)
            self.recordings.save(
                task,
                self.model,
                key,
                {
                    "model": self.model,
                    "date": time.strftime("%Y-%m-%d"),
                    "task": task,
                    "output": json.loads(text),
                    "input_tokens": usage.input_tokens,
                    "output_tokens": usage.output_tokens,
                },
            )
        return ModelResult(output, "claude", self.model, usage.input_tokens, usage.output_tokens)


class FakeModel:
    """Keyless mode: replay a recorded answer when one exists, else run the rule-based reader."""

    def __init__(
        self, recordings: Recordings | None = None, replay_model: str = DEFAULT_MODEL
    ) -> None:
        self.recordings = recordings
        self.replay_model = replay_model

    def run(
        self,
        task: str,
        system: str,
        user: str,
        schema: type[T],
        rule_based: Callable[[], T] | None = None,
    ) -> ModelResult:
        if self.recordings is not None:
            key = request_key(task, self.replay_model, system, user, schema)
            rec = self.recordings.load(task, self.replay_model, key)
            if rec is not None:
                telemetry.event("model_call", task=task, model=self.replay_model, source="recorded")
                return ModelResult(
                    schema.model_validate(rec["output"]),
                    "recorded",
                    f"{rec['model']}@{rec['date']}",
                )
        if rule_based is None:
            raise LookupError(f"no recording and no rule-based reader for task {task}")
        telemetry.event("model_call", task=task, model="rule-based", source="rule-based")
        return ModelResult(rule_based(), "rule-based", "rule-based")


def strict_schema(schema: type[BaseModel]) -> dict[str, Any]:
    """JSON schema with additionalProperties false on every object (required by structured
    outputs) and every property required."""
    s = schema.model_json_schema()

    def walk(node: Any) -> None:
        if isinstance(node, dict):
            if node.get("type") == "object" or "properties" in node:
                node["additionalProperties"] = False
                node["required"] = list(node.get("properties", {}))
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    walk(s)
    return s


def model_from_env(recordings: Recordings | None) -> Model:
    """Claude when ANTHROPIC_API_KEY is set, otherwise the keyless fake model."""
    if os.environ.get("ANTHROPIC_API_KEY"):
        return ClaudeModel(os.environ.get("CIMIENTO_MODEL", DEFAULT_MODEL), recordings=recordings)
    return FakeModel(recordings)
