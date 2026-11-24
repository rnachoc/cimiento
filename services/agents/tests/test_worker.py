"""Intake worker: an uploaded tender goes through the usual analysis and the core gets a report."""

from __future__ import annotations

import hashlib

import pytest

from cimiento_agents.core_client import ForbiddenCall, RecordingCore, check_allowed
from cimiento_agents.llm import FakeModel
from cimiento_agents.tender_agents import load_rates
from cimiento_agents.worker import run_once, serve


class FakeIntakes:
    """The core's intake queue in memory: jobs with their PDFs, and the reports received."""

    def __init__(self, jobs: list[dict], files: dict[tuple[int, str], bytes]) -> None:
        self.jobs = jobs
        self.files = files
        self.reports: list[dict] = []

    def claim(self):
        return self.jobs.pop(0) if self.jobs else None

    def document(self, intake_id: int, name: str) -> bytes:
        return self.files[(intake_id, name)]

    def report(self, intake_id, ok, tender_id, proposals, message) -> None:
        self.reports.append(
            {
                "id": intake_id,
                "ok": ok,
                "tenderId": tender_id,
                "proposals": proposals,
                "message": message,
            }
        )


def job_from(folder, intake_id: int, code: str, tamper: str | None = None):
    files, refs = {}, []
    for name in ("pliego", "anexo", "mediciones"):
        content = (folder / f"{name}.pdf").read_bytes()
        files[(intake_id, name)] = content
        digest = hashlib.sha256(content).hexdigest()
        refs.append(
            {"name": name, "sha256": "0" * 64 if name == tamper else digest, "size": len(content)}
        )
    job = {
        "id": intake_id,
        "code": code,
        "title": "Reforma del mercado",
        "plannedStart": "2027-01-04",
        "documents": refs,
    }
    return job, files


def test_an_uploaded_tender_is_analysed_and_reported(dev_split, tmp_path):
    job, files = job_from(dev_split / "tenders" / "D-001", 7, "NUEVA-1")
    intakes, core = FakeIntakes([job], files), RecordingCore()
    rates = load_rates(dev_split / "company" / "tarifas.json")
    assert run_once(intakes, core, FakeModel(None), rates, tmp_path) is True
    # the tender is registered with the title the person typed, and proposals wait for review
    assert core.tenders["NUEVA-1"]["id"] == 1
    assert len(core.proposals) > 3 and {p["status"] for p in core.proposals} == {"PENDING_REVIEW"}
    report = intakes.reports[0]
    assert (
        report["ok"] is True
        and report["tenderId"] == 1
        and report["proposals"] == len(core.proposals)
    )
    assert "propuestas" in report["message"]
    # the temporary copy of the documents is gone
    assert not (tmp_path / "NUEVA-1").exists()
    # an empty queue
    assert run_once(intakes, core, FakeModel(None), rates, tmp_path) is False


def test_a_document_that_does_not_match_its_checksum_fails_the_job_only(dev_split, tmp_path):
    bad, files = job_from(dev_split / "tenders" / "D-001", 1, "MAL-1", tamper="anexo")
    good, more = job_from(dev_split / "tenders" / "D-002", 2, "BIEN-2")
    intakes, core = FakeIntakes([bad, good], {**files, **more}), RecordingCore()
    assert serve(intakes, core, FakeModel(None), None, once=True) == 2
    assert intakes.reports[0] == {
        "id": 1,
        "ok": False,
        "tenderId": None,
        "proposals": None,
        "message": "El análisis falló: ValueError",
    }
    assert intakes.reports[1]["ok"] is True
    assert "MAL-1" not in core.tenders


def test_a_code_cannot_escape_the_work_folder(dev_split, tmp_path):
    job, files = job_from(dev_split / "tenders" / "D-001", 3, "../fuera")
    outside = tmp_path / "fuera"
    outside.mkdir()
    (outside / "keep.txt").write_text("x")
    (tmp_path / "w").mkdir()
    intakes = FakeIntakes([job], files)
    run_once(intakes, RecordingCore(), FakeModel(None), None, tmp_path / "w")
    assert intakes.reports[0]["ok"] is False
    # nothing outside the work folder was written or deleted
    assert (outside / "keep.txt").exists() and len(list(outside.iterdir())) == 1


def test_the_worker_may_claim_and_report_but_not_decide():
    check_allowed("POST", "/api/intakes/claim")
    check_allowed("POST", "/api/intakes/12/result")
    check_allowed("GET", "/api/intakes/12/documents/pliego")
    with pytest.raises(ForbiddenCall):
        check_allowed("POST", "/api/intakes")
    with pytest.raises(ForbiddenCall):
        check_allowed("POST", "/api/proposals/5/decision")


def test_the_worker_waits_while_the_core_is_not_reachable(monkeypatch):
    import httpx

    from cimiento_agents import worker as w

    calls = {"n": 0}

    class Flaky(FakeIntakes):
        def claim(self):
            calls["n"] += 1
            if calls["n"] == 1:
                raise httpx.ConnectError("refused")
            raise KeyboardInterrupt  # stop the endless loop after the retry

    monkeypatch.setattr(w.time, "sleep", lambda s: None)
    with pytest.raises(KeyboardInterrupt):
        serve(Flaky([], {}), RecordingCore(), FakeModel(None), None)
    assert calls["n"] == 2
