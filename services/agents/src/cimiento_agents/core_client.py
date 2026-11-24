"""Client for the core API with the AGENT key.

The agent may only register tenders, submit proposals and read. Any other call is refused
here, before it reaches the network (the core enforces the same policy on its side).
"""

from __future__ import annotations

import hashlib
import json
import re
from typing import Any, Protocol

import httpx

from . import telemetry, tracing

ALLOWED = (
    ("POST", re.compile(r"^/api/tenders$")),
    ("POST", re.compile(r"^/api/proposals$")),
    # intake worker: take an uploaded tender and report how the analysis went (ADR 0018)
    ("POST", re.compile(r"^/api/intakes/claim$")),
    ("POST", re.compile(r"^/api/intakes/\d+/result$")),
    ("GET", re.compile(r"^/api/[A-Za-z0-9/_?=&.-]+$")),
)


class ForbiddenCall(PermissionError):
    pass


def check_allowed(method: str, path: str) -> None:
    if not any(m == method and p.match(path) for m, p in ALLOWED):
        telemetry.event("core_call_refused", method=method)
        raise ForbiddenCall(f"agents may not call {method} {path}")


def idempotency_key(body: dict[str, Any]) -> str:
    canonical = json.dumps(body, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(canonical.encode()).hexdigest()


class Core(Protocol):
    def register_tender(
        self, project_code: str, code: str, title: str, term_months: int | None, planned_start: str
    ) -> dict[str, Any]: ...

    def submit(
        self,
        tender_id: int,
        type_: str,
        payload: dict[str, Any],
        citations: list[dict[str, Any]],
        signals: list[str],
    ) -> dict[str, Any]: ...


class CoreClient:
    def __init__(self, base_url: str, agent_key: str, http: httpx.Client | None = None) -> None:
        self.http = http or httpx.Client(base_url=base_url, timeout=30)
        self.headers = {"X-Api-Key": agent_key}

    def _call(
        self, method: str, path: str, body: dict[str, Any] | None = None, idem: str | None = None
    ) -> dict[str, Any]:
        check_allowed(method, path)
        headers = dict(self.headers)
        if idem:
            headers["Idempotency-Key"] = idem
        route = re.sub(r"/\d+", "/{id}", path.split("?")[0])
        with (
            telemetry.timed("core_call", method=method) as t,
            tracing.span(
                f"{method} {route}", **{"http.request.method": method, "url.template": route}
            ) as s,
        ):
            r = self.http.request(method, path, json=body, headers=headers)
            t["status"] = r.status_code
            tracing.set_attributes(s, **{"http.response.status_code": r.status_code})
        r.raise_for_status()
        return r.json()

    def register_tender(
        self, project_code: str, code: str, title: str, term_months: int | None, planned_start: str
    ) -> dict[str, Any]:
        return self._call(
            "POST",
            "/api/tenders",
            {
                "projectCode": project_code,
                "code": code,
                "title": title,
                "contractTermMonths": term_months,
                "plannedStart": planned_start,
            },
        )

    def submit(
        self,
        tender_id: int,
        type_: str,
        payload: dict[str, Any],
        citations: list[dict[str, Any]],
        signals: list[str],
    ) -> dict[str, Any]:
        tracing.record_proposal(type_)
        body = {
            "tenderId": tender_id,
            "type": type_,
            "payload": payload,
            "citations": citations,
            "signals": signals,
        }
        return self._call("POST", "/api/proposals", body, idempotency_key(body))

    def get(self, path: str) -> Any:
        return self._call("GET", path)


class RecordingCore:
    """In-process stand-in for evals and tests: applies the same allow list, stores calls."""

    def __init__(self) -> None:
        self.tenders: dict[str, dict[str, Any]] = {}
        self.proposals: list[dict[str, Any]] = []

    def register_tender(
        self, project_code: str, code: str, title: str, term_months: int | None, planned_start: str
    ) -> dict[str, Any]:
        check_allowed("POST", "/api/tenders")
        t = self.tenders.setdefault(
            code, {"id": len(self.tenders) + 1, "code": code, "contractTermMonths": term_months}
        )
        return t

    def submit(
        self,
        tender_id: int,
        type_: str,
        payload: dict[str, Any],
        citations: list[dict[str, Any]],
        signals: list[str],
    ) -> dict[str, Any]:
        check_allowed("POST", "/api/proposals")
        with tracing.span(
            "POST /api/proposals",
            **{
                "http.request.method": "POST",
                "url.template": "/api/proposals",
                "cimiento.proposal.type": type_,
            },
        ):
            tracing.record_proposal(type_)
        p = {
            "id": len(self.proposals) + 1,
            "tenderId": tender_id,
            "type": type_,
            "payload": payload,
            "citations": citations,
            "signals": signals,
            "status": "PENDING_REVIEW",
        }
        self.proposals.append(p)
        return p
