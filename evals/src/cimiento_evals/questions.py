"""Synthetic questions built from the ground truth.

Questions are worded differently from the documents on purpose, and the eval split uses
its own wording (EVAL_TEMPLATES), so thresholds tuned on dev questions are not tuned on
eval questions. Unanswerable questions come from the ground truth list.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Question:
    tender: str
    kind: str  # campo | anexo | medicion | sin_respuesta
    field: str
    text: str
    document: str | None
    page: int | None
    quote: str | None


DEV_TEMPLATES: dict[str, list[str]] = {
    "plazo_ejecucion_meses": [
        "¿Cuál es el plazo de ejecución de las obras?",
        "¿En cuántos meses hay que terminar la obra?",
    ],
    "presupuesto_base_eur": [
        "¿Cuál es el presupuesto base de licitación?",
        "¿Cuál es el importe máximo sin IVA?",
    ],
    "garantia_definitiva_pct": ["¿Qué garantía definitiva se exige?"],
    "solvencia_economica_eur": ["¿Qué volumen anual de negocios se exige?"],
    "solvencia_tecnica_obras": ["¿Cuántas obras similares hay que acreditar?"],
    "criterio_precio_pct": ["¿Qué peso tiene el precio en la adjudicación?"],
    "criterio_plazo_pct": ["¿Cuánto puntúa la reducción de plazo?"],
    "criterio_mejoras_pct": ["¿Cuánto valen las mejoras técnicas?"],
    "penalidad_demora_eur_por_mil_dia": ["¿Qué penalidad se aplica por demora?"],
    "subcontratacion_max_pct": ["¿Cuál es el límite de subcontratación?"],
    "fecha_presentacion": ["¿Hasta cuándo se pueden presentar ofertas?"],
    "visita_obligatoria": ["¿Es obligatoria la visita al emplazamiento?"],
    "anexo_plazo_ejecucion_meses": ["¿Qué plazo prevé el programa de trabajos?"],
    "medicion": ["¿Qué cantidad de {desc} hay en {loc}?"],
}

EVAL_TEMPLATES: dict[str, list[str]] = {
    "plazo_ejecucion_meses": [
        "¿De cuánto tiempo dispone el contratista para ejecutar los trabajos?"
    ],
    "presupuesto_base_eur": ["¿Por cuánto dinero como máximo se puede ofertar?"],
    "garantia_definitiva_pct": ["¿Qué fianza tiene que depositar el adjudicatario?"],
    "solvencia_economica_eur": ["¿Cuánto tiene que facturar al año la empresa como mínimo?"],
    "solvencia_tecnica_obras": ["¿Qué experiencia previa en obras parecidas se pide?"],
    "criterio_precio_pct": ["¿Cuántos puntos da la oferta económica?"],
    "criterio_plazo_pct": ["¿Se valora acortar la duración de la obra y cuánto?"],
    "criterio_mejoras_pct": ["¿Qué puntuación tienen las propuestas adicionales?"],
    "penalidad_demora_eur_por_mil_dia": ["¿Qué sanción hay por cada día de retraso?"],
    "subcontratacion_max_pct": ["¿Qué parte de la obra se puede encargar a terceros?"],
    "fecha_presentacion": ["¿Cuál es el último día para entregar la proposición?"],
    "visita_obligatoria": ["¿Hay que visitar el lugar de las obras antes de ofertar?"],
    "anexo_plazo_ejecucion_meses": ["¿Cuánto dura la obra según el anexo técnico?"],
    "medicion": ["¿Cuántas unidades de {desc} se miden en {loc}?"],
}


def questions_for_split(split_dir: Path, split: str) -> list[Question]:
    templates = EVAL_TEMPLATES if split == "eval" else DEV_TEMPLATES
    rates = json.loads((split_dir / "company" / "tarifas.json").read_text(encoding="utf-8"))
    descriptions = {p["codigo"]: p["descripcion"].lower() for p in rates["partidas"]}
    out: list[Question] = []
    for gt_path in sorted((split_dir / "ground_truth").glob("*.json")):
        gt = json.loads(gt_path.read_text(encoding="utf-8"))
        if gt["gemelo_de"]:
            continue  # twins repeat their tender
        tid = gt["id"]
        for name, campo in gt["campos"].items():
            kind = "anexo" if campo["documento"] == "anexo" else "campo"
            for text in templates[name]:
                out.append(
                    Question(
                        tid, kind, name, text, campo["documento"], campo["pagina"], campo["texto"]
                    )
                )
        # one measurement question per work item: first non-duplicate row
        for m in gt["mediciones"]:
            row = next(f for f in m["filas"] if not f["duplicada"])
            loc = row["texto"].split("|")[2].strip()
            for text in templates["medicion"]:
                out.append(
                    Question(
                        tid,
                        "medicion",
                        m["codigo"],
                        text.format(desc=descriptions[m["codigo"]], loc=loc),
                        row["documento"],
                        row["pagina"],
                        row["texto"],
                    )
                )
        for text in gt["preguntas_sin_respuesta"]:
            out.append(Question(tid, "sin_respuesta", "-", text, None, None, None))
    return out
