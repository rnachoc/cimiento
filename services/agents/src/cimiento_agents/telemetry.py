"""Structured events without content.

Only identifiers, counts, timings and flags are accepted. A string value must look like an
identifier (no spaces, at most 64 chars), so document text cannot slip into logs or spans.
"""

from __future__ import annotations

import json
import logging
import re
import time
from collections.abc import Iterator
from contextlib import contextmanager

logger = logging.getLogger("cimiento.agents")

_ID = re.compile(r"^[A-Za-z0-9_.:/@-]{1,64}$")
EVENTS: list[dict[str, object]] = []  # in-process sink, read by tests and evals


class ContentInTelemetry(ValueError):
    pass


def _check(key: str, value: object) -> None:
    if not _ID.match(key):
        raise ContentInTelemetry(f"bad telemetry key {key!r}")
    if value is None or isinstance(value, (bool, int, float)):
        return
    if isinstance(value, str) and _ID.match(value):
        return
    raise ContentInTelemetry(f"telemetry field {key!r} must be an id, count, timing or flag")


def event(name: str, **fields: object) -> None:
    _check("event", name)
    for k, v in fields.items():
        _check(k, v)
    record = {"event": name, **fields}
    EVENTS.append(record)
    logger.info(json.dumps(record, sort_keys=True))


@contextmanager
def timed(name: str, **fields: object) -> Iterator[dict[str, object]]:
    """Emit `name` with `ms` once the block ends; the block may add counts to the dict."""
    extra: dict[str, object] = {}
    start = time.perf_counter()
    try:
        yield extra
    finally:
        event(name, ms=round((time.perf_counter() - start) * 1000, 1), **fields, **extra)
