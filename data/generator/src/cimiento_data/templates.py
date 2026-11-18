"""Wording templates for tender clauses.

Each field has several variants per style (prose, list, table). Variants marked
`eval_only` are used only in the eval split, so a reader tuned on the dev split
meets wording it has never seen. Value formats follow the same rule.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from .numwords import to_words
from .textnorm import format_es

STYLES = ("prose", "list", "table")

MONTHS_ES = (
    "enero",
    "febrero",
    "marzo",
    "abril",
    "mayo",
    "junio",
    "julio",
    "agosto",
    "septiembre",
    "octubre",
    "noviembre",
    "diciembre",
)


@dataclass(frozen=True)
class Variant:
    id: str
    style: str
    text: str  # format string with {v}
    eval_only: bool = False


@dataclass(frozen=True)
class ValueFormat:
    id: str
    eval_only: bool = False


# ---------------------------------------------------------------- value formats

VALUE_FORMATS: dict[str, tuple[ValueFormat, ...]] = {
    "months": (
        ValueFormat("months.words_num"),
        ValueFormat("months.num"),
        ValueFormat("months.words", eval_only=True),
    ),
    "money": (
        ValueFormat("money.dots"),
        ValueFormat("money.plain_eur"),
        ValueFormat("money.spaces", eval_only=True),
    ),
    "pct": (
        ValueFormat("pct.num"),
        ValueFormat("pct.words_num"),
        ValueFormat("pct.words", eval_only=True),
    ),
    "count": (
        ValueFormat("count.words_num"),
        ValueFormat("count.num"),
        ValueFormat("count.words", eval_only=True),
    ),
    "date": (
        ValueFormat("date.long"),
        ValueFormat("date.slash"),
        ValueFormat("date.dash", eval_only=True),
    ),
    "permil": (
        ValueFormat("permil.euros"),
        ValueFormat("permil.eur"),
        ValueFormat("permil.trim", eval_only=True),
    ),
}


def render_value(fmt_id: str, value: object) -> str:
    kind, _, style = fmt_id.partition(".")
    if kind == "months":
        n = int(value)  # type: ignore[arg-type]
        noun = "mes" if n == 1 else "meses"
        words = to_words(n, apocope=True)
        return {
            "words_num": f"{words} ({n}) {noun}",
            "num": f"{n} {noun}",
            "words": f"{words} {noun}",
        }[style]
    if kind == "money":
        v = Decimal(value)  # type: ignore[arg-type]
        return {
            "dots": f"{format_es(v, 2, 'dots')} euros",
            "plain_eur": f"{format_es(v, 2, 'plain')} EUR",
            "spaces": f"{format_es(v, 2, 'spaces')} euros",
        }[style]
    if kind == "pct":
        n = int(value)  # type: ignore[arg-type]
        return {
            "num": f"{n} %",
            "words_num": f"{to_words(n)} por ciento ({n} %)",
            "words": f"{to_words(n)} por ciento",
        }[style]
    if kind == "count":
        n = int(value)  # type: ignore[arg-type]
        words = to_words(n, apocope=True)
        return {"words_num": f"{words} ({n})", "num": f"{n}", "words": words}[style]
    if kind == "date":
        d: date = value  # type: ignore[assignment]
        return {
            "long": f"{d.day} de {MONTHS_ES[d.month - 1]} de {d.year}",
            "slash": f"{d.day:02d}/{d.month:02d}/{d.year}",
            "dash": f"{d.day:02d}-{d.month:02d}-{d.year}",
        }[style]
    if kind == "permil":
        v = Decimal(value)  # type: ignore[arg-type]
        return {
            "euros": f"{format_es(v, 2, 'plain')} euros",
            "eur": f"{format_es(v, 2, 'plain')} EUR",
            "trim": f"{format_es(v, 2, 'trim')} euros",
        }[style]
    raise KeyError(fmt_id)


# ---------------------------------------------------------------- fields


@dataclass(frozen=True)
class FieldDef:
    name: str
    clause: str  # clause group key
    value_kind: str | None  # key in VALUE_FORMATS, None for booleans
    variants: tuple[Variant, ...]


def _v(fid: str, n: int, style: str, text: str, eval_only: bool = False) -> Variant:
    return Variant(f"{fid}.{n}", style, text, eval_only)


FIELDS: tuple[FieldDef, ...] = (
    FieldDef(
        "plazo_ejecucion_meses",
        "plazo",
        "months",
        (
            _v(
                "plazo",
                1,
                "prose",
                "El plazo de ejecución de las obras será de {v}, contado desde el "
                "día siguiente a la firma del acta de comprobación del replanteo.",
            ),
            _v(
                "plazo",
                2,
                "prose",
                "Las obras deberán ejecutarse en un plazo máximo de {v} a partir de "
                "la comprobación del replanteo.",
            ),
            _v("plazo", 3, "list", "Plazo de ejecución: {v}."),
            _v("plazo", 4, "table", "Plazo de ejecución | {v}"),
            _v(
                "plazo",
                5,
                "prose",
                "La duración prevista del contrato es de {v}, sin prórroga salvo "
                "causa justificada.",
                eval_only=True,
            ),
            _v("plazo", 6, "table", "Duración máxima de los trabajos | {v}", eval_only=True),
            _v("plazo", 7, "list", "Tiempo máximo para terminar la obra: {v}.", eval_only=True),
        ),
    ),
    FieldDef(
        "presupuesto_base_eur",
        "presupuesto",
        "money",
        (
            _v(
                "presupuesto",
                1,
                "prose",
                "El presupuesto base de licitación asciende a {v}, IVA excluido.",
            ),
            _v(
                "presupuesto",
                2,
                "prose",
                "El importe máximo de licitación es de {v}, sin incluir el IVA.",
            ),
            _v("presupuesto", 3, "list", "Presupuesto base de licitación (IVA excluido): {v}."),
            _v("presupuesto", 4, "table", "Presupuesto base de licitación sin IVA | {v}"),
            _v(
                "presupuesto",
                5,
                "prose",
                "Las ofertas no podrán superar la cantidad de {v}, IVA no incluido.",
                eval_only=True,
            ),
            _v("presupuesto", 6, "table", "Importe máximo sin impuestos | {v}", eval_only=True),
            _v(
                "presupuesto",
                7,
                "list",
                "Límite económico de la oferta, sin IVA: {v}.",
                eval_only=True,
            ),
        ),
    ),
    FieldDef(
        "garantia_definitiva_pct",
        "garantia",
        "pct",
        (
            _v(
                "garantia",
                1,
                "prose",
                "El adjudicatario deberá constituir una garantía definitiva del "
                "{v} del precio final ofertado, IVA excluido.",
            ),
            _v("garantia", 2, "list", "Garantía definitiva: {v} del importe de adjudicación."),
            _v(
                "garantia", 3, "table", "Garantía definitiva sobre el importe de adjudicación | {v}"
            ),
            _v(
                "garantia",
                4,
                "prose",
                "Se exigirá una garantía definitiva equivalente al {v} del "
                "importe de adjudicación.",
            ),
            _v(
                "garantia",
                5,
                "prose",
                "Quien resulte adjudicatario depositará como fianza el {v} del "
                "precio de adjudicación.",
                eval_only=True,
            ),
            _v("garantia", 6, "table", "Fianza definitiva | {v}", eval_only=True),
        ),
    ),
    FieldDef(
        "solvencia_economica_eur",
        "solvencia",
        "money",
        (
            _v(
                "solv_eco",
                1,
                "prose",
                "Para acreditar la solvencia económica, el volumen anual de "
                "negocios en alguno de los tres últimos ejercicios deberá ser al menos de {v}.",
            ),
            _v("solv_eco", 2, "list", "Volumen anual de negocios mínimo: {v}."),
            _v("solv_eco", 3, "table", "Volumen anual de negocios mínimo | {v}"),
            _v(
                "solv_eco",
                4,
                "prose",
                "La facturación anual mínima exigida en uno de los tres últimos años es de {v}.",
                eval_only=True,
            ),
            _v("solv_eco", 5, "table", "Cifra de negocio anual exigida | {v}", eval_only=True),
        ),
    ),
    FieldDef(
        "solvencia_tecnica_obras",
        "solvencia",
        "count",
        (
            _v(
                "solv_tec",
                1,
                "prose",
                "La solvencia técnica se acreditará con {v} obras de "
                "naturaleza similar ejecutadas en los últimos cinco años.",
            ),
            _v("solv_tec", 2, "list", "Obras similares exigidas en los últimos cinco años: {v}."),
            _v("solv_tec", 3, "table", "Obras similares en los últimos cinco años | {v}"),
            _v(
                "solv_tec",
                4,
                "prose",
                "Se deberán acreditar al menos {v} obras parecidas terminadas "
                "en el último quinquenio.",
                eval_only=True,
            ),
            _v(
                "solv_tec",
                5,
                "table",
                "Experiencia exigida (obras similares, cinco años) | {v}",
                eval_only=True,
            ),
        ),
    ),
    FieldDef(
        "criterio_precio_pct",
        "criterios",
        "pct",
        (
            _v(
                "crit_precio",
                1,
                "prose",
                "La oferta económica se valorará con un {v} de la puntuación total.",
            ),
            _v("crit_precio", 2, "list", "Precio: {v}."),
            _v("crit_precio", 3, "table", "Precio | {v}"),
            _v(
                "crit_precio",
                4,
                "prose",
                "Al precio ofertado le corresponde el {v} de los puntos.",
                eval_only=True,
            ),
            _v("crit_precio", 5, "table", "Oferta económica | {v}", eval_only=True),
        ),
    ),
    FieldDef(
        "criterio_plazo_pct",
        "criterios",
        "pct",
        (
            _v(
                "crit_plazo",
                1,
                "prose",
                "La reducción del plazo de ejecución se valorará con un {v} "
                "de la puntuación total.",
            ),
            _v("crit_plazo", 2, "list", "Reducción de plazo: {v}."),
            _v("crit_plazo", 3, "table", "Reducción de plazo | {v}"),
            _v(
                "crit_plazo",
                4,
                "prose",
                "Acortar la duración de la obra puede sumar hasta el {v} de los puntos.",
                eval_only=True,
            ),
            _v("crit_plazo", 5, "table", "Menor plazo ofertado | {v}", eval_only=True),
        ),
    ),
    FieldDef(
        "criterio_mejoras_pct",
        "criterios",
        "pct",
        (
            _v(
                "crit_mejoras",
                1,
                "prose",
                "Las mejoras técnicas propuestas se valorarán con un {v} de la puntuación total.",
            ),
            _v("crit_mejoras", 2, "list", "Mejoras: {v}."),
            _v("crit_mejoras", 3, "table", "Mejoras técnicas | {v}"),
            _v(
                "crit_mejoras",
                4,
                "prose",
                "Las propuestas adicionales sin coste pueden sumar hasta el {v} de los puntos.",
                eval_only=True,
            ),
            _v("crit_mejoras", 5, "table", "Prestaciones adicionales | {v}", eval_only=True),
        ),
    ),
    FieldDef(
        "penalidad_demora_eur_por_mil_dia",
        "penalidades",
        "permil",
        (
            _v(
                "penalidad",
                1,
                "prose",
                "En caso de demora imputable al contratista se impondrán "
                "penalidades diarias de {v} por cada 1.000 euros del precio del contrato, IVA "
                "excluido.",
            ),
            _v(
                "penalidad",
                2,
                "list",
                "Penalidad diaria por demora: {v} por cada 1.000 euros del precio.",
            ),
            _v("penalidad", 3, "table", "Penalidad diaria por demora por cada 1.000 euros | {v}"),
            _v(
                "penalidad",
                4,
                "prose",
                "Cada día de retraso supondrá una penalización de {v} por "
                "cada 1.000 euros de precio.",
                eval_only=True,
            ),
            _v(
                "penalidad",
                5,
                "table",
                "Sanción por día de retraso (por 1.000 euros) | {v}",
                eval_only=True,
            ),
        ),
    ),
    FieldDef(
        "subcontratacion_max_pct",
        "subcontratacion",
        "pct",
        (
            _v(
                "subcon",
                1,
                "prose",
                "El contratista podrá subcontratar como máximo el {v} del importe de adjudicación.",
            ),
            _v("subcon", 2, "list", "Límite de subcontratación: {v} del importe de adjudicación."),
            _v("subcon", 3, "table", "Límite de subcontratación | {v}"),
            _v(
                "subcon",
                4,
                "prose",
                "No se podrá encargar a terceros más del {v} de la obra adjudicada.",
                eval_only=True,
            ),
            _v("subcon", 5, "table", "Parte máxima ejecutable por terceros | {v}", eval_only=True),
        ),
    ),
    FieldDef(
        "fecha_presentacion",
        "presentacion",
        "date",
        (
            _v(
                "presentacion",
                1,
                "prose",
                "Las proposiciones se presentarán antes de las 14:00 horas del {v}.",
            ),
            _v("presentacion", 2, "list", "Fecha límite de presentación de ofertas: {v}."),
            _v("presentacion", 3, "table", "Fecha límite de presentación | {v}"),
            _v(
                "presentacion",
                4,
                "prose",
                "El plazo para entregar las ofertas termina el {v} a las 14:00.",
                eval_only=True,
            ),
            _v(
                "presentacion",
                5,
                "table",
                "Último día para presentar ofertas | {v}",
                eval_only=True,
            ),
        ),
    ),
    FieldDef(
        "visita_obligatoria",
        "visita",
        None,
        (
            _v(
                "visita_si",
                1,
                "prose",
                "Será obligatoria la visita al emplazamiento de las obras, "
                "que tendrá lugar el {v}.",
            ),
            _v("visita_si", 2, "list", "Visita obligatoria al emplazamiento: sí, el {v}."),
            _v("visita_si", 3, "table", "Visita obligatoria al emplazamiento | Sí, el {v}"),
            _v(
                "visita_si",
                4,
                "prose",
                "Los licitadores deberán visitar el lugar de las obras el {v}; "
                "sin esa visita la oferta no será admitida.",
                eval_only=True,
            ),
            _v(
                "visita_no",
                1,
                "prose",
                "No se exige la visita previa al emplazamiento de las obras.",
            ),
            _v("visita_no", 2, "list", "Visita obligatoria al emplazamiento: no."),
            _v("visita_no", 3, "table", "Visita obligatoria al emplazamiento | No"),
            _v(
                "visita_no",
                4,
                "prose",
                "La visita al lugar de las obras es voluntaria.",
                eval_only=True,
            ),
        ),
    ),
)

FIELD_BY_NAME = {f.name: f for f in FIELDS}

# Clause groups: key -> (title, intro sentence or "")
CLAUSES: dict[str, tuple[str, str]] = {
    "plazo": ("Plazo de ejecución", ""),
    "presupuesto": ("Presupuesto base de licitación", ""),
    "garantia": ("Garantías", "No se exige garantía provisional."),
    "solvencia": (
        "Solvencia del licitador",
        "Los licitadores acreditarán su solvencia del modo siguiente.",
    ),
    "criterios": (
        "Criterios de adjudicación",
        "La adjudicación se realizará a la oferta con "
        "mejor relación calidad-precio según los criterios siguientes.",
    ),
    "penalidades": ("Penalidades por demora", ""),
    "subcontratacion": ("Subcontratación", ""),
    "presentacion": ("Presentación de proposiciones", ""),
    "visita": ("Visita al emplazamiento", ""),
}

# Filler clauses without expected fields. Must not mention any UNANSWERABLE term.
FILLER_CLAUSES: tuple[tuple[str, str], ...] = (
    (
        "Régimen jurídico",
        "El contrato tiene carácter administrativo y se regirá por este pliego, "
        "por el proyecto aprobado y por la normativa de contratos del sector público aplicable.",
    ),
    (
        "Procedimiento de adjudicación",
        "El contrato se adjudicará por procedimiento abierto, en el "
        "que todo empresario interesado podrá presentar una proposición.",
    ),
    (
        "Documentación administrativa",
        "Los licitadores presentarán la declaración responsable, la "
        "oferta económica y la documentación técnica en sobres separados.",
    ),
    (
        "Confidencialidad",
        "El órgano de contratación no divulgará la información facilitada por los "
        "licitadores que estos hayan designado como confidencial.",
    ),
    (
        "Recepción de las obras",
        "A la terminación de las obras se levantará el acta de recepción en "
        "presencia de la dirección facultativa y del contratista.",
    ),
    (
        "Seguridad y salud",
        "El contratista elaborará un plan de seguridad y salud en el trabajo que "
        "deberá ser aprobado antes del inicio de las obras.",
    ),
    (
        "Abono de las obras",
        "El abono se realizará mediante certificaciones mensuales expedidas por "
        "la dirección facultativa sobre la obra realmente ejecutada.",
    ),
    (
        "Mesa de contratación",
        "La mesa de contratación calificará la documentación presentada y "
        "podrá conceder un plazo de tres días hábiles para subsanar defectos materiales.",
    ),
    (
        "Ofertas anormalmente bajas",
        "Se considerarán anormalmente bajas las ofertas que se "
        "encuentren en los supuestos previstos en la normativa aplicable. Se dará audiencia al "
        "licitador antes de su exclusión.",
    ),
    (
        "Obligaciones laborales y sociales",
        "El contratista cumplirá las disposiciones vigentes en "
        "materia laboral, de seguridad social y de prevención de riesgos laborales respecto del "
        "personal que emplee en la obra.",
    ),
    (
        "Modificación del contrato",
        "El contrato solo podrá modificarse en los supuestos y con los "
        "límites establecidos en la normativa de contratos del sector público.",
    ),
)

NUMBERING_STYLES = (
    ("num.clausula", False),
    ("num.dot", False),
    ("num.articulo", False),
    ("num.paren", True),
)


def clause_heading(style: str, n: int, title: str) -> str:
    return {
        "num.clausula": f"Cláusula {n}. {title}",
        "num.dot": f"{n}. {title.upper()}",
        "num.articulo": f"Artículo {n}. {title}",
        "num.paren": f"{n}) {title}",
    }[style]


# Annex sentence stating the execution period (used for contradictions).
ANNEX_TERM: tuple[Variant, ...] = (
    Variant(
        "anexo_plazo.1",
        "prose",
        "El programa de trabajos se ha previsto para un plazo total de {v}.",
    ),
    Variant(
        "anexo_plazo.2", "prose", "Duración estimada de la obra según el programa de trabajos: {v}."
    ),
    Variant(
        "anexo_plazo.3",
        "prose",
        "Se prevé terminar la obra en {v} desde su comienzo.",
        eval_only=True,
    ),
)


def allowed(items, split: str):
    """Variants usable in a split: dev never sees eval_only items."""
    return [i for i in items if split == "eval" or not i.eval_only]
