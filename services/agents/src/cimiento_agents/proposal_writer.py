"""Proposal writer: draft of the technical proposal (memoria técnica) in the company template.

Every sentence of the draft names its source: a cited field of the tender or a fixed
company statement. It is always a draft for a person to review.
"""

from __future__ import annotations

from .citations import Citation

TEMPLATE = [
    (
        "Objeto",
        "Constructora Brisa presenta su propuesta técnica para la licitación {tender}.",
        None,
    ),
    (
        "Plazo",
        "La empresa se compromete a ejecutar las obras en el plazo de {plazo_ejecucion_meses} meses fijado en el pliego.",
        "plazo_ejecucion_meses",
    ),
    (
        "Medios",
        "Se acreditan {solvencia_tecnica_obras} obras similares en los últimos cinco años, conforme a la solvencia técnica exigida.",
        "solvencia_tecnica_obras",
    ),
    (
        "Subcontratación",
        "La subcontratación no superará el {subcontratacion_max_pct} % del importe de adjudicación.",
        "subcontratacion_max_pct",
    ),
    (
        "Visita",
        "La empresa ha previsto la visita al emplazamiento indicada en el pliego.",
        "visita_obligatoria",
    ),
]


def proposal_draft(
    tender: str, fields: dict[str, str], citations: dict[str, Citation]
) -> list[dict]:
    sections = []
    for title, text, source in TEMPLATE:
        if source is not None and source not in fields:
            continue  # no claim without a source
        if source == "visita_obligatoria" and fields[source] == "no":
            continue
        sections.append(
            {
                "title": title,
                "text": text.format(tender=tender, **fields),
                "source": citations[source].model_dump() if source else {"company_statement": True},
            }
        )
    return sections
