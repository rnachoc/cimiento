"""OpenTelemetry tracing and metrics with the GenAI semantic conventions.

One trace per tender; one span per agent, model call (gen_ai.* attributes: provider, model,
input and output tokens) and core request. Every attribute goes through the same check as
`telemetry.event`: identifiers, counts, timings and flags only, never document text.

Export: OTLP over HTTP when OTEL_EXPORTER_OTLP_ENDPOINT is set; a JSON-lines file when
CIMIENTO_TRACE_FILE is set (used by the cost report and the CI check). Nothing otherwise.
"""

from __future__ import annotations

import functools
import json
import os
from collections.abc import Callable, Iterator, Sequence
from contextlib import contextmanager
from typing import Any

from opentelemetry import metrics, trace
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import ReadableSpan, TracerProvider
from opentelemetry.sdk.trace.export import (
    BatchSpanProcessor,
    SimpleSpanProcessor,
    SpanExporter,
    SpanExportResult,
)

from .telemetry import _check

SERVICE = "cimiento-agents"
_configured = False


class JsonLinesExporter(SpanExporter):
    """Appends finished spans to a file, one JSON object per line."""

    def __init__(self, path: str) -> None:
        self.path = path

    def export(self, spans: Sequence[ReadableSpan]) -> SpanExportResult:
        with open(self.path, "a", encoding="utf-8") as fh:
            for s in spans:
                fh.write(
                    json.dumps(
                        {
                            "name": s.name,
                            "trace_id": f"{s.context.trace_id:032x}",
                            "span_id": f"{s.context.span_id:016x}",
                            "parent_id": f"{s.parent.span_id:016x}" if s.parent else None,
                            "start": s.start_time,
                            "end": s.end_time,
                            "attributes": dict(s.attributes or {}),
                        }
                    )
                    + "\n"
                )
        return SpanExportResult.SUCCESS

    def shutdown(self) -> None:
        return None


def configure(extra_exporter: SpanExporter | None = None) -> None:
    """Set up providers once per process from the environment."""
    global _configured
    if _configured:
        if extra_exporter is not None:
            trace.get_tracer_provider().add_span_processor(SimpleSpanProcessor(extra_exporter))  # type: ignore[attr-defined]
        return
    resource = Resource.create({"service.name": SERVICE})
    provider = TracerProvider(resource=resource)
    readers = []
    if os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT"):
        from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
        from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
        from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader

        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
        readers.append(
            PeriodicExportingMetricReader(OTLPMetricExporter(), export_interval_millis=5000)
        )
    if os.environ.get("CIMIENTO_TRACE_FILE"):
        provider.add_span_processor(
            SimpleSpanProcessor(JsonLinesExporter(os.environ["CIMIENTO_TRACE_FILE"]))
        )
    if extra_exporter is not None:
        provider.add_span_processor(SimpleSpanProcessor(extra_exporter))
    trace.set_tracer_provider(provider)
    metrics.set_meter_provider(MeterProvider(resource=resource, metric_readers=readers))
    _configured = True


def _attrs(attributes: dict[str, Any]) -> dict[str, Any]:
    clean = {k: v for k, v in attributes.items() if v is not None}
    for k, v in clean.items():
        _check(k.replace(".", "_"), v)  # same rule as telemetry events: no content
    return clean


@contextmanager
def span(name: str, **attributes: Any) -> Iterator[trace.Span]:
    tracer = trace.get_tracer("cimiento.agents")
    with tracer.start_as_current_span(name, attributes=_attrs(attributes)) as s:
        yield s


def set_attributes(s: trace.Span, **attributes: Any) -> None:
    s.set_attributes(_attrs(attributes))


_meter = None
_instruments: dict[str, Any] = {}


def _instrument(kind: str, name: str, unit: str, description: str) -> Any:
    global _meter
    if _meter is None:
        _meter = metrics.get_meter("cimiento.agents")
    if name not in _instruments:
        make = _meter.create_counter if kind == "counter" else _meter.create_histogram
        _instruments[name] = make(name, unit=unit, description=description)
    return _instruments[name]


def record_model_call(
    provider: str, model: str, input_tokens: int, output_tokens: int, seconds: float
) -> None:
    """GenAI metrics: gen_ai.client.token.usage (histogram per call, by token type) and
    gen_ai.client.operation.duration."""
    attrs = _attrs(
        {
            "gen_ai.operation.name": "chat",
            "gen_ai.provider.name": provider,
            "gen_ai.request.model": model,
        }
    )
    tokens = _instrument(
        "histogram", "gen_ai.client.token.usage", "{token}", "Tokens per model call"
    )
    tokens.record(input_tokens, {**attrs, "gen_ai.token.type": "input"})
    tokens.record(output_tokens, {**attrs, "gen_ai.token.type": "output"})
    _instrument("histogram", "gen_ai.client.operation.duration", "s", "Model call duration").record(
        seconds, attrs
    )


def record_proposal(type_: str) -> None:
    _instrument(
        "counter", "cimiento.proposals.submitted", "{proposal}", "Proposals sent to the core"
    ).add(1, _attrs({"cimiento.proposal.type": type_}))


def traced(name: str) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
    """Wrap a function in a span named `name` (no attributes from its arguments)."""

    def wrap(fn: Callable[..., Any]) -> Callable[..., Any]:
        @functools.wraps(fn)
        def inner(*args: Any, **kwargs: Any) -> Any:
            with span(name):
                return fn(*args, **kwargs)

        return inner

    return wrap
