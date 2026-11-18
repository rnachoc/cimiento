"""Report writer: daily report, RFI, weekly report and minutes. Always drafts.

Every sentence carries the ids of the records it comes from. `check_sources` rejects a
report with any sentence that has no source or cites a record that does not exist, so no
report reaches the core with an unsupported claim. Templates are filled by code; a model may
rephrase sentences but never add a sentence without sources.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .citations import Citation
from .progress import ProgressReport

KINDS = ("parte_diario", "rfi", "informe_semanal", "acta")


@dataclass
class Sentence:
    text: str
    sources: list[str]


@dataclass
class Report:
    kind: str
    title: str
    sentences: list[Sentence] = field(default_factory=list)

    def payload(self) -> dict:
        return {
            "kind": self.kind,
            "title": self.title,
            "status": "draft",
            "sentences": [{"text": s.text, "sources": s.sources} for s in self.sentences],
        }


class UnsupportedClaim(ValueError):
    pass


def check_sources(report: Report, known: set[str]) -> None:
    if report.kind not in KINDS:
        raise ValueError(f"unknown report kind {report.kind}")
    if not report.sentences:
        raise UnsupportedClaim(f"{report.kind} has no content")
    for s in report.sentences:
        if not s.sources:
            raise UnsupportedClaim(f"sentence without source: {s.text[:60]}")
        missing = [x for x in s.sources if x not in known]
        if missing:
            raise UnsupportedClaim(f"unknown sources {missing}")


def citation_id(c: Citation) -> str:
    return f"doc:{c.document}:p{c.page}"


def known_records(
    progress: ProgressReport | None, citations: list[Citation], missing_fields: list[str]
) -> set[str]:
    ids = {citation_id(c) for c in citations} | {f"campo:{f}:no_encontrado" for f in missing_fields}
    if progress:
        ids |= {d.source for d in progress.deviations} | {i["record"] for i in progress.incidents}
        ids |= {progress.record} | {f"parte:{d}" for d in progress.report_dates}
    return ids


def daily_report(progress: ProgressReport, date_record: str) -> Report:
    incidents = [i for i in progress.incidents if i["record"] == date_record]
    r = Report("parte_diario", f"Parte diario {date_record.split(':', 1)[1]}")
    if not incidents:
        r.sentences.append(Sentence("Sin incidencias registradas en el parte.", [date_record]))
    for i in incidents:
        r.sentences.append(Sentence(i["text"], [i["record"]]))
    return r


def weekly_report(progress: ProgressReport) -> Report:
    r = Report("informe_semanal", f"Informe semanal, semana {progress.week} ({progress.cut_date})")
    for d in progress.deviations:
        if d.delay_days == 0:
            continue
        bound = "al menos " if d.delay_is_lower_bound else ""
        text = (
            f"{d.activity}: avance real {d.actual_pct} % frente a {d.planned_pct} % planificado "
            f"(SPI {d.spi}), retraso de {bound}{d.delay_days} días laborables con holgura de "
            f"{d.total_float}."
        )
        if d.alarm:
            text += " Alarma: afecta al fin de obra."
        sources = [d.source] + [
            i["record"] for i in progress.incidents if i["activity"] == d.activity
        ][:3]
        r.sentences.append(Sentence(text, sources))
    alarms = [d for d in progress.deviations if d.alarm]
    if alarms:
        r.sentences.append(
            Sentence(
                f"Impacto estimado en el fin de obra: {progress.impact_days} días laborables.",
                [d.source for d in alarms],
            )
        )
    if not r.sentences:
        r.sentences.append(
            Sentence(
                "Todas las actividades avanzan según lo planificado.",
                [d.source for d in progress.deviations] or [progress.record],
            )
        )
    return r


def rfi(missing_fields: list[str], contradictions: list[list[Citation]]) -> Report | None:
    """Requests for information from gaps (fields not found) and contradictions."""
    r = Report("rfi", "Solicitud de información")
    for f in missing_fields:
        r.sentences.append(
            Sentence(
                f"Se solicita aclarar el dato '{f}', que no consta en los documentos.",
                [f"campo:{f}:no_encontrado"],
            )
        )
    for pair in contradictions:
        r.sentences.append(
            Sentence(
                "Se solicita aclarar la discrepancia entre "
                + " y ".join(f"{c.document} (página {c.page})" for c in pair)
                + ".",
                [citation_id(c) for c in pair],
            )
        )
    return r if r.sentences else None


def minutes(progress: ProgressReport) -> Report:
    r = Report("acta", f"Acta de reunión de seguimiento, semana {progress.week}")
    alarms = [d for d in progress.deviations if d.alarm]
    r.sentences.append(
        Sentence(
            f"Se revisa el avance a fecha {progress.cut_date}: {len(progress.deviations)} actividades con "
            f"avance, {len(alarms)} con alarma.",
            [d.source for d in progress.deviations] or [progress.record],
        )
    )
    for d in alarms:
        r.sentences.append(
            Sentence(f"Se acuerda un plan de recuperación para {d.activity}.", [d.source])
        )
    return r
