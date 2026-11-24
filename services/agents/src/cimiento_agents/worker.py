"""Intake worker: takes the tenders people upload in the console, analyses them, reports back.

The core keeps the uploaded PDFs and a queue of jobs. The worker claims one job at a time with the
AGENT key, writes its documents to a temporary folder named after the tender code, runs the usual
analysis (every proposal goes through the core rules and waits for a person) and tells the core how
it went. It never sends document text in its report: only counts, the recommendation or the
name of an error.
"""

from __future__ import annotations

import hashlib
import shutil
import tempfile
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any, Protocol

import httpx

from . import telemetry
from .coordinator import analyse_tender
from .core_client import Core, CoreClient, check_allowed
from .llm import Model
from .tender_agents import Rate

DOCUMENTS = ("pliego", "anexo", "mediciones")


class Intakes(Protocol):
    def claim(self) -> dict[str, Any] | None: ...

    def document(self, intake_id: int, name: str) -> bytes: ...

    def report(
        self, intake_id: int, ok: bool, tender_id: int | None, proposals: int | None, message: str
    ) -> None: ...


class IntakeClient:
    """The three intake calls of the core, with the same allow list as every agent call."""

    def __init__(self, core: CoreClient) -> None:
        self.http = core.http
        self.headers = core.headers

    def claim(self) -> dict[str, Any] | None:
        check_allowed("POST", "/api/intakes/claim")
        r = self.http.post("/api/intakes/claim", headers=self.headers)
        r.raise_for_status()
        return None if r.status_code == 204 else r.json()

    def document(self, intake_id: int, name: str) -> bytes:
        path = f"/api/intakes/{intake_id}/documents/{name}"
        check_allowed("GET", path)
        r = self.http.get(path, headers=self.headers)
        r.raise_for_status()
        return r.content

    def report(
        self, intake_id: int, ok: bool, tender_id: int | None, proposals: int | None, message: str
    ) -> None:
        path = f"/api/intakes/{intake_id}/result"
        check_allowed("POST", path)
        body = {"ok": ok, "tenderId": tender_id, "proposals": proposals, "message": message[:300]}
        self.http.post(path, json=body, headers=self.headers).raise_for_status()


def run_once(
    intakes: Intakes,
    core: Core,
    model: Model,
    rates: dict[str, Rate] | None,
    workdir: Path,
    analyse: Callable[..., Any] = analyse_tender,
) -> bool:
    """Process one job. Returns False when the queue is empty."""
    job = intakes.claim()
    if job is None:
        return False
    intake_id, code = int(job["id"]), str(job["code"])
    folder = workdir / code
    # the core only accepts plain codes; checked again so nothing is written or deleted elsewhere
    safe = folder.resolve().parent == workdir.resolve()
    try:
        if not safe:
            raise ValueError("unsafe tender code")
        folder.mkdir(parents=True, exist_ok=False)
        for ref in job.get("documents", []):
            name = ref["name"]
            if name not in DOCUMENTS:
                raise ValueError(f"unexpected document {name}")
            content = intakes.document(intake_id, name)
            if hashlib.sha256(content).hexdigest() != ref["sha256"]:
                raise ValueError(f"{name} does not match its checksum")
            (folder / f"{name}.pdf").write_bytes(content)
        with telemetry.timed("intake", tender=code):
            run = analyse(folder, model, core, str(job["plannedStart"]), rates, title=job["title"])
        summary = (
            f"{len(run.fields)} datos con cita, {len(run.proposal_ids)} propuestas, "
            f"recomendación {run.recommendation or 'sin calcular'}"
        )
        intakes.report(intake_id, True, run.tender_id, len(run.proposal_ids), summary)
    except Exception as e:  # noqa: BLE001 - the job fails, the worker keeps running
        telemetry.event("intake_failed", tender=code, error=type(e).__name__)
        intakes.report(intake_id, False, None, None, f"El análisis falló: {type(e).__name__}")
    finally:
        if safe:
            shutil.rmtree(folder, ignore_errors=True)
    return True


def serve(
    intakes: Intakes,
    core: Core,
    model: Model,
    rates: dict[str, Rate] | None,
    poll_seconds: float = 3.0,
    once: bool = False,
) -> int:
    """Work through the queue; with once=True stop when it is empty. Returns jobs processed."""
    done = 0
    workdir = Path(tempfile.mkdtemp(prefix="cimiento-intake-"))
    try:
        while True:
            try:
                if run_once(intakes, core, model, rates, workdir):
                    done += 1
                    continue
            except httpx.HTTPError as e:
                # the core is not reachable yet (or restarting): wait and ask again
                telemetry.event("intake_core_unreachable", error=type(e).__name__)
                if once:
                    raise
            if once:
                return done
            time.sleep(poll_seconds)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
