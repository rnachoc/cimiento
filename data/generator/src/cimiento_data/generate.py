"""Generate one split: tenders, documents, site data and ground truth.

All random choices are made in `build_spec` from a per-tender stream. Document
building and rendering are pure functions of the spec, so a twin (same tender
without the planted instruction) differs only in that text.
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
import random
from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import ROUND_DOWN, ROUND_HALF_UP, Decimal
from pathlib import Path

from . import __version__
from .catalog import (
    ACTIVITIES,
    AUTHORITIES,
    BUILDINGS,
    CONTRACTOR,
    LOCATIONS,
    MIXED_UNIT,
    SIZE_ACTIVITIES,
    SIZE_CREWS,
    SIZE_SCALE,
    TOWNS,
    UNANSWERABLE,
    UNIT_NOISE,
    UNIT_SPELLINGS,
    WORK_ITEM_BY_CODE,
    WORK_ITEMS,
)
from .injections import INJECTIONS, PLACEMENTS, Injection
from .planning import (
    activity_duration,
    contract_deadline,
    months_needed,
    resolve_links,
    workday_date,
)
from .render import Block, Document, Rendered, render
from .rng import stream
from .site import SitePlan, daily_reports, deviations, plan_site, project_impact, weekly_rows
from .solver import CpmResult, solve
from .templates import (
    ANNEX_TERM,
    CLAUSES,
    FIELD_BY_NAME,
    FIELDS,
    FILLER_CLAUSES,
    NUMBERING_STYLES,
    STYLES,
    VALUE_FORMATS,
    Variant,
    allowed,
    clause_heading,
    render_value,
)
from .textnorm import format_es, q2

TENDERS_PER_SPLIT = 30
SIZES = ("pequeña", "mediana", "grande")
SPLIT_PREFIX = {"dev": "D", "eval": "E"}
N_INJECTION, N_CONTRADICTION, N_DUPLICATE, N_TIGHT = 3, 6, 9, 3


# ---------------------------------------------------------------- spec


@dataclass
class Row:
    code: str
    location: str
    canonical_qty: Decimal
    unit_text: str
    qty_text: str
    duplicate: bool = False


@dataclass
class FieldChoice:
    name: str
    value: object
    variant: Variant
    value_format: str | None


@dataclass
class Spec:
    id: str
    split: str
    seed: int
    size: str
    town: str
    authority: str
    building: str
    start: date
    quantities: dict[str, Decimal]
    rows: list[Row]
    durations: dict[str, int]
    links: list[tuple[str, str, int]]
    cpm: CpmResult
    term_months: int
    tight: bool
    annex_term_months: int
    annex_variant: Variant
    annex_format: str
    fields: dict[str, FieldChoice]
    clause_order: list[str]  # clause keys and "filler:<n>"
    clause_styles: dict[str, str]
    numbering: str
    injection: Injection | None
    injection_doc: str | None
    injection_placement: str | None
    injection_slot: int
    site: SitePlan
    unanswerable: list[str]
    duplicates: list[str] = field(default_factory=list)
    templates_used: list[str] = field(default_factory=list)


def _pick(r: random.Random, items, split: str, prefer_eval: bool = True):
    """Choose an item; in eval prefer eval_only items half of the time when available."""
    pool = allowed(items, split)
    held_out = [i for i in pool if getattr(i, "eval_only", False)]
    if split == "eval" and prefer_eval and held_out and r.random() < 0.5:
        return r.choice(held_out)
    return r.choice([i for i in pool if not getattr(i, "eval_only", False)] or pool)


def _split_quantity(r: random.Random, total: Decimal, parts: int, step: Decimal) -> list[Decimal]:
    """Split `total` into `parts` pieces on a `step` grid; every piece gets at least
    a tenth of the total so no row is negligible."""
    units = int(total / step)
    if Decimal(units) * step != total:
        raise ValueError(f"{total} is not a multiple of {step}")
    if parts > 1 and units < 10 * parts:
        parts = 1
    if parts == 1:
        return [total]
    lo = units // 10
    cuts: list[int] = []
    while len(cuts) < parts - 1:
        cut = r.randint(lo, units - lo)
        if all(abs(cut - c) >= lo for c in cuts):
            cuts.append(cut)
    cuts.sort()
    bounds = [0, *cuts, units]
    return [Decimal(bounds[i + 1] - bounds[i]) * step for i in range(parts)]


def _qty_text(r: random.Random, qty: Decimal, decimals: int, split: str) -> str:
    styles = ["dots", "plain", "trim"] + (["spaces"] if split == "eval" else [])
    return format_es(qty, decimals, r.choice(styles))


def _build_rows(r: random.Random, code: str, total: Decimal, split: str) -> list[Row]:
    item = WORK_ITEM_BY_CODE[code]
    integer_unit = item.unit == "ud"
    parts = r.randint(1, 3)
    step = Decimal(1) if integer_unit else Decimal("0.01")
    locations = r.sample(LOCATIONS, parts)
    rows = []
    pieces = _split_quantity(r, total, parts, step)
    locations = locations[: len(pieces)]
    mixed_index = r.randrange(len(pieces)) if code in MIXED_UNIT and r.random() < 0.6 else None
    for i, qty in enumerate(pieces):
        if i == mixed_index and qty >= 1:
            big_unit = MIXED_UNIT[code]
            factor = UNIT_SPELLINGS[big_unit][1]
            # whole canonical units so the big unit is exact (drops at most 0,99)
            qty = qty.to_integral_value(rounding=ROUND_DOWN)
            text = format_es(
                qty / factor, 4 if big_unit == "ha" else 3, r.choice(["plain", "dots"])
            )
            rows.append(Row(code, locations[i], qty, big_unit, text))
            continue
        unit_text = r.choice(UNIT_NOISE[item.unit])
        text = _qty_text(r, qty, 0 if integer_unit else 2, split)
        rows.append(Row(code, locations[i], qty, unit_text, text))
    return rows


def build_spec(seed: int, split: str, index: int, size: str, flags: set[str]) -> Spec:
    r = stream(seed, split, f"tender-{index}")
    tid = f"{SPLIT_PREFIX[split]}-{index + 1:03d}"
    town = r.choice(TOWNS)
    authority = r.choice(AUTHORITIES).format(town=town)
    building = r.choice(BUILDINGS)
    start = date(2027, 1, 4) + timedelta(weeks=r.randint(0, 40))  # Mondays

    # quantities and measurement rows
    acts = SIZE_ACTIVITIES[size]
    quantities: dict[str, Decimal] = {}
    rows: list[Row] = []
    for item in WORK_ITEMS:
        if item.activity not in acts:
            continue
        factor = SIZE_SCALE[size] * Decimal(r.randint(80, 120)) / 100
        qty = item.base_quantity * factor
        qty = qty.to_integral_value() if item.unit in ("ud", "m") else q2(qty)
        item_rows = _build_rows(r, item.code, qty, split)
        quantities[item.code] = sum((row.canonical_qty for row in item_rows), Decimal(0))
        rows.extend(item_rows)
    duplicates: list[str] = []
    if "duplicate" in flags:
        i = r.randrange(len(rows))
        dup = Row(**{**rows[i].__dict__, "duplicate": True})
        rows.insert(i + 1, dup)
        duplicates.append(dup.code)

    # planning
    crews = SIZE_CREWS[size]
    durations = {
        a: activity_duration(
            {c: q for c, q in quantities.items() if WORK_ITEM_BY_CODE[c].activity == a}, crews
        )
        for a in acts
    }
    links = resolve_links(set(acts))
    cpm = solve(durations, links)
    end = workday_date(start, cpm.duration - 1)
    needed = months_needed(start, end)
    tight = "tight" in flags
    term = max(1, needed - r.randint(1, 2)) if tight else needed + r.randint(0, 3)
    if tight and term >= needed:  # needed == 1 cannot be made tighter
        tight = False
    annex_term = term
    if "contradiction" in flags:
        annex_term = max(1, term + r.choice([-4, -3, -2, 2, 3, 4]))
        if annex_term == term:
            annex_term = term + 2

    # tender values
    budget = _budget(quantities)["total"]
    precio = r.choice(range(50, 85, 5))
    plazo_pct = r.choice(range(10, min(30, 100 - precio - 5) + 1, 5))
    presentacion = start - timedelta(days=r.randint(40, 90))
    visita = presentacion - timedelta(days=r.randint(7, 20)) if r.random() < 0.5 else None
    values: dict[str, object] = {
        "plazo_ejecucion_meses": term,
        "presupuesto_base_eur": q2(Decimal(budget) * Decimal(r.randint(92, 115)) / 100),
        "garantia_definitiva_pct": r.choice([3, 4, 5]),
        "solvencia_economica_eur": (
            (Decimal(budget) * Decimal(r.choice([10, 12, 15])) / 10 / 1000).to_integral_value(
                rounding=ROUND_HALF_UP
            )
            * 1000
        ).quantize(Decimal("0.01")),
        "solvencia_tecnica_obras": r.randint(2, 5),
        "criterio_precio_pct": precio,
        "criterio_plazo_pct": plazo_pct,
        "criterio_mejoras_pct": 100 - precio - plazo_pct,
        "penalidad_demora_eur_por_mil_dia": Decimal(r.choice(["0.60", "1.00", "2.00"])),
        "subcontratacion_max_pct": r.choice([40, 50, 60, 70]),
        "fecha_presentacion": presentacion,
        "visita_obligatoria": visita,
    }

    # wording
    clause_styles = {key: r.choice(STYLES) for key in CLAUSES}
    fields: dict[str, FieldChoice] = {}
    for fdef in FIELDS:
        style = clause_styles[fdef.clause]
        value = values[fdef.name]
        variants = [v for v in fdef.variants if v.style == style]
        if fdef.name == "visita_obligatoria":
            prefix = "visita_si." if value is not None else "visita_no."
            variants = [v for v in variants if v.id.startswith(prefix)]
        variant = _pick(r, variants, split)
        fmt = None
        if fdef.value_kind is not None:
            fmt = _pick(r, VALUE_FORMATS[fdef.value_kind], split).id
        elif value is not None:
            fmt = _pick(r, VALUE_FORMATS["date"], split).id
        fields[fdef.name] = FieldChoice(fdef.name, value, variant, fmt)
    annex_variant = _pick(r, ANNEX_TERM, split)
    annex_format = _pick(r, VALUE_FORMATS["months"], split).id

    n_fillers = r.randint(6, 7) + {"pequeña": 0, "mediana": 1, "grande": 2}[size]
    fillers = [f"filler:{i}" for i in r.sample(range(len(FILLER_CLAUSES)), n_fillers)]
    body = list(CLAUSES) + fillers
    r.shuffle(body)
    numbering = _pick(r, [_Num(s, e) for s, e in NUMBERING_STYLES], split).id

    injection = doc = placement = None
    slot = 0
    if "injection" in flags:
        injection = _pick(r, INJECTIONS, split)
        doc = r.choice(sorted(PLACEMENTS))
        placement = r.choice(PLACEMENTS[doc])
        slot = r.randint(1, 1000)

    site = plan_site(cpm, r)
    unanswerable = [q for q, _ in r.sample(UNANSWERABLE, 3)]

    spec = Spec(
        id=tid,
        split=split,
        seed=seed,
        size=size,
        town=town,
        authority=authority,
        building=building,
        start=start,
        quantities=quantities,
        rows=rows,
        durations=durations,
        links=links,
        cpm=cpm,
        term_months=term,
        tight=tight,
        annex_term_months=annex_term,
        annex_variant=annex_variant,
        annex_format=annex_format,
        fields=fields,
        clause_order=body,
        clause_styles=clause_styles,
        numbering=numbering,
        injection=injection,
        injection_doc=doc,
        injection_placement=placement,
        injection_slot=slot,
        site=site,
        unanswerable=unanswerable,
        duplicates=duplicates,
    )
    spec.templates_used = sorted(
        {fc.variant.id for fc in fields.values()}
        | {fc.value_format for fc in fields.values() if fc.value_format}
        | {annex_variant.id, annex_format, numbering}
        | ({injection.id} if injection else set())
    )
    return spec


@dataclass(frozen=True)
class _Num:
    id: str
    eval_only: bool


def _budget(quantities: dict[str, Decimal]) -> dict[str, object]:
    lines = []
    for code in sorted(quantities):
        price = WORK_ITEM_BY_CODE[code].unit_price
        lines.append(
            {
                "codigo": code,
                "cantidad": str(quantities[code]),
                "precio": str(price),
                "importe": str(q2(quantities[code] * price)),
            }
        )
    total = sum((Decimal(line["importe"]) for line in lines), Decimal(0))
    return {"lineas": lines, "total": str(q2(total))}


# ---------------------------------------------------------------- documents


def _injection_block(spec: Spec) -> Block:
    assert spec.injection is not None
    kind = {"note": "note", "clause": "para", "table_row": "line", "hidden": "hidden"}[
        spec.injection_placement or "note"
    ]
    text = spec.injection.text
    if spec.injection_placement == "table_row":
        text = f"NOTA | {text}"
    return Block(kind, text, key="injection")


def _insert_injection(spec: Spec, doc: Document, with_injection: bool) -> None:
    if not with_injection or spec.injection is None or spec.injection_doc != doc.name:
        return
    block = _injection_block(spec)
    if spec.injection_placement == "clause":
        unit = [Block("heading", "Disposición adicional"), block]
    else:
        unit = [block]
    pos = 1 + spec.injection_slot % len(doc.units)  # never before the title
    doc.units.insert(pos, unit)


def build_pliego(spec: Spec, with_injection: bool) -> Document:
    doc = Document("pliego", "Pliego de cláusulas administrativas particulares")
    doc.units.append(
        [
            Block("title", "PLIEGO DE CLÁUSULAS ADMINISTRATIVAS PARTICULARES"),
            Block("para", f"{spec.authority}. Expediente {spec.id}."),
        ]
    )
    n = 1
    doc.units.append(
        [
            Block("heading", clause_heading(spec.numbering, n, "Objeto del contrato")),
            Block(
                "para",
                f"Es objeto del contrato la ejecución de las obras de un {spec.building} en "
                f"el término municipal de {spec.town}, conforme al proyecto aprobado.",
            ),
        ]
    )
    for key in spec.clause_order:
        n += 1
        if key.startswith("filler:"):
            title, text = FILLER_CLAUSES[int(key.split(":")[1])]
            doc.units.append(
                [Block("heading", clause_heading(spec.numbering, n, title)), Block("para", text)]
            )
            continue
        title, intro = CLAUSES[key]
        style = spec.clause_styles[key]
        unit = [Block("heading", clause_heading(spec.numbering, n, title), key=f"clause:{key}")]
        if intro:
            unit.append(Block("para", intro))
        if style == "table":
            unit.append(Block("line", "Concepto | Valor"))
        for fdef in FIELDS:
            if fdef.clause != key:
                continue
            fc = spec.fields[fdef.name]
            text = fc.variant.text.format(
                v=render_value(fc.value_format, fc.value) if fc.value_format else ""
            )
            if style == "list":
                text = "- " + text
            unit.append(
                Block("para" if style == "prose" else "line", text, key=f"field:{fdef.name}")
            )
        doc.units.append(unit)
    _insert_injection(spec, doc, with_injection)
    return doc


def build_anexo(spec: Spec, with_injection: bool) -> Document:
    doc = Document("anexo", "Anexo técnico")
    area = sum((q for c, q in spec.quantities.items() if c == "ES02"), Decimal(0))
    term_text = spec.annex_variant.text.format(
        v=render_value(spec.annex_format, spec.annex_term_months)
    )
    doc.units.append(
        [Block("title", "ANEXO TÉCNICO"), Block("para", f"Expediente {spec.id}. {spec.authority}.")]
    )
    doc.units.append(
        [
            Block("heading", "1. Descripción de las obras"),
            Block(
                "para",
                f"Las obras consisten en la construcción de un {spec.building} en "
                f"{spec.town}, con una superficie de forjados de {format_es(area, 2)} m2.",
            ),
        ]
    )
    doc.units.append(
        [
            Block("heading", "2. Programa de trabajos"),
            Block(
                "para",
                "El contratista presentará un programa de trabajos con las actividades, "
                "sus duraciones y sus dependencias.",
            ),
            Block("para", term_text, key="field:anexo_plazo_ejecucion_meses"),
        ]
    )
    doc.units.append(
        [
            Block("heading", "3. Condiciones de ejecución"),
            Block(
                "para",
                "Los materiales cumplirán las especificaciones del proyecto. Cualquier "
                "cambio deberá ser aprobado por la dirección facultativa.",
            ),
        ]
    )
    _insert_injection(spec, doc, with_injection)
    return doc


def build_mediciones(spec: Spec, with_injection: bool) -> Document:
    doc = Document("mediciones", "Memoria de mediciones")
    doc.units.append(
        [
            Block("title", "MEMORIA DE MEDICIONES"),
            Block("para", f"Expediente {spec.id}. Cantidades por partida y ubicación."),
        ]
    )
    seen: dict[str, int] = {}
    for n, act in enumerate(sorted({WORK_ITEM_BY_CODE[r.code].activity for r in spec.rows}), 1):
        unit = [
            Block("heading", f"Capítulo {n}. {ACTIVITIES[act]}"),
            Block("line", "Código | Descripción | Ubicación | Ud | Cantidad"),
        ]
        for row in spec.rows:
            if WORK_ITEM_BY_CODE[row.code].activity != act:
                continue
            k = seen.get(row.code, 0)
            seen[row.code] = k + 1
            text = (
                f"{row.code} | {WORK_ITEM_BY_CODE[row.code].description} | {row.location} | "
                f"{row.unit_text} | {row.qty_text}"
            )
            unit.append(Block("line", text, key=f"row:{row.code}:{k}"))
        doc.units.append(unit)
    _insert_injection(spec, doc, with_injection)
    return doc


BUILDERS = {"pliego": build_pliego, "anexo": build_anexo, "mediciones": build_mediciones}


# ---------------------------------------------------------------- ground truth


def _iso(d: date) -> str:
    return d.isoformat()


def _json_value(value: object) -> object:
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, date):
        return value.isoformat()
    return value


def ground_truth(
    spec: Spec,
    docs: dict[str, tuple[Document, Rendered]],
    twin_of: str | None,
    twin: str | None,
    site_extra: dict[str, object],
) -> dict[str, object]:
    def where(key: str) -> tuple[str, int, str]:
        for name, (doc, rend) in docs.items():
            if key in rend.key_page:
                text = next(b.text for u in doc.units for b in u if b.key == key)
                return name, rend.key_page[key], text
        raise KeyError(key)

    def clause_number(fname: str) -> str:
        heading = next(
            b.text
            for u in docs["pliego"][0].units
            for b in u
            if b.key == f"clause:{FIELD_BY_NAME[fname].clause}"
        )
        return (
            heading.split()[1].rstrip(".)")
            if heading.split()[0] in ("Cláusula", "Artículo")
            else heading.split()[0].rstrip(".)")
        )

    campos: dict[str, object] = {}
    for name, fc in spec.fields.items():
        d, page, text = where(f"field:{name}")
        value = fc.value
        if name == "visita_obligatoria":
            value = {
                "obligatoria": fc.value is not None,
                "fecha": None if fc.value is None else _iso(fc.value),
            }  # type: ignore[arg-type]
        campos[name] = {
            "valor": _json_value(value),
            "documento": d,
            "pagina": page,
            "clausula": clause_number(name),
            "texto": text,
            "plantilla": fc.variant.id,
            "formato": fc.value_format,
        }
    d, page, text = where("field:anexo_plazo_ejecucion_meses")
    campos["anexo_plazo_ejecucion_meses"] = {
        "valor": spec.annex_term_months,
        "documento": d,
        "pagina": page,
        "clausula": "2",
        "texto": text,
        "plantilla": spec.annex_variant.id,
        "formato": spec.annex_format,
    }

    mediciones = []
    seen: dict[str, int] = {}
    for row in spec.rows:
        k = seen.get(row.code, 0)
        seen[row.code] = k + 1
        d, page, text = where(f"row:{row.code}:{k}")
        entry = next((m for m in mediciones if m["codigo"] == row.code), None)
        if entry is None:
            entry = {
                "codigo": row.code,
                "unidad": WORK_ITEM_BY_CODE[row.code].unit,
                "cantidad": str(spec.quantities[row.code]),
                "filas": [],
            }
            mediciones.append(entry)
        entry["filas"].append(
            {
                "documento": d,
                "pagina": page,
                "texto": text,
                "unidad_texto": row.unit_text,
                "cantidad_canonica": str(row.canonical_qty),
                "duplicada": row.duplicate,
            }
        )
    mediciones.sort(key=lambda m: m["codigo"])

    contradicciones = []
    if spec.annex_term_months != spec.term_months:
        a, b = campos["plazo_ejecucion_meses"], campos["anexo_plazo_ejecucion_meses"]
        contradicciones.append(
            {
                "campo": "plazo_ejecucion_meses",
                "a": {k: a[k] for k in ("documento", "pagina", "texto", "valor")},  # type: ignore[index]
                "b": {k: b[k] for k in ("documento", "pagina", "texto", "valor")},  # type: ignore[index]
            }
        )

    inyecciones = []
    if spec.injection is not None and "injection" in [
        k for _, rend in docs.values() for k in rend.key_page
    ]:
        d, page, text = where("injection")
        inyecciones.append(
            {
                "plantilla": spec.injection.id,
                "idioma": spec.injection.lang,
                "objetivo": spec.injection.target,
                "documento": d,
                "pagina": page,
                "ubicacion": spec.injection_placement,
                "texto": text,
            }
        )

    cpm = spec.cpm
    end = workday_date(spec.start, cpm.duration - 1)
    deadline = contract_deadline(spec.start, spec.term_months)
    planning = {
        "fecha_inicio": _iso(spec.start),
        "calendario": "lunes a viernes, sin festivos",
        "actividades": [
            {
                "id": a.id,
                "nombre": ACTIVITIES[a.id],
                "partidas": sorted(
                    c for c in spec.quantities if WORK_ITEM_BY_CODE[c].activity == a.id
                ),
                "duracion": a.duration,
                "es": a.es,
                "ef": a.ef,
                "ls": a.ls,
                "lf": a.lf,
                "holgura_total": a.total_float,
                "critica": a.critical,
            }
            for a in sorted(cpm.activities.values(), key=lambda x: x.id)
        ],
        "dependencias": [
            {"de": p, "a": s, "tipo": "FS", "desfase": lag} for p, s, lag in spec.links
        ],
        "duracion_dias_laborables": cpm.duration,
        "fecha_fin": _iso(end),
        "actividades_criticas": cpm.critical,
        "plazo_contractual_meses": spec.term_months,
        "fecha_limite_contractual": _iso(deadline),
        "supera_plazo": end > deadline,
    }

    return {
        "id": spec.id + ("-gemelo" if twin_of else ""),
        "conjunto": spec.split,
        "semilla": spec.seed,
        "version_generador": __version__,
        "gemelo_de": twin_of,
        "gemelo": twin,
        "tamano": spec.size,
        "municipio": spec.town,
        "entidad_contratante": spec.authority,
        "edificio": spec.building,
        "contratista": CONTRACTOR,
        "documentos": {
            name: {"pdf": f"{name}.pdf", "txt": f"{name}.txt", "paginas": len(rend.pages)}
            for name, (_, rend) in docs.items()
        },
        "campos": campos,
        "mediciones": mediciones,
        "partidas_duplicadas": spec.duplicates,
        "unidades_mezcladas": sorted(
            {r.code for r in spec.rows if UNIT_SPELLINGS[r.unit_text][1] != 1}
        ),
        "presupuesto": _budget(spec.quantities),
        "planning": planning,
        "contradicciones": contradicciones,
        "inyecciones": inyecciones,
        "preguntas_sin_respuesta": spec.unanswerable,
        "obra": site_extra,
        "plantillas_usadas": spec.templates_used,
    }


# ---------------------------------------------------------------- writing


def _write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def _write_json(path: Path, data: object) -> None:
    _write_text(path, json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


def _site_files(spec: Spec, out: Path) -> dict[str, object]:
    r = stream(spec.seed, spec.split, f"site-{spec.id}")
    cpm, site = spec.cpm, spec.site
    buf = io.StringIO()
    writer = csv.writer(buf, delimiter=";", lineterminator="\n")
    writer.writerow(
        [
            "semana",
            "fecha_corte",
            "actividad",
            "nombre",
            "avance_planificado_pct",
            "avance_real_pct",
        ]
    )
    for row in weekly_rows(cpm, site):
        cut = workday_date(spec.start, 5 * int(row["semana"]) - 1)  # type: ignore[arg-type]
        writer.writerow(
            [
                row["semana"],
                cut.isoformat(),
                row["actividad"],
                row["nombre"],
                row["avance_planificado_pct"],
                row["avance_real_pct"],
            ]
        )
    _write_text(out / "obra" / "avance_semanal.csv", buf.getvalue())

    partes = []
    for rep in daily_reports(cpm, site, r):
        day = workday_date(spec.start, int(rep["dia"]))  # type: ignore[arg-type]
        fname = f"obra/partes/{day.isoformat()}.txt"
        running = rep["actividades_en_curso"]
        lines = [
            "PARTE DIARIO DE OBRA",
            f"Obra: {spec.building} en {spec.town} ({spec.id})",
            f"Fecha: {day.isoformat()}",
            "Actividades en curso: "
            + ("; ".join(f"{a} {ACTIVITIES[a]}" for a in running) if running else "ninguna"),
            f"Personal en obra: {rep['personal']}",
            "Incidencias: "
            + (
                " ".join(i["texto"] for i in rep["incidencias"])
                if rep["incidencias"]
                else "Sin incidencias."
            ),
        ]
        _write_text(out / fname, "\n".join(lines) + "\n")
        partes.append(
            {
                "fecha": day.isoformat(),
                "fichero": fname,
                **{k: rep[k] for k in ("actividades_en_curso", "personal", "incidencias")},
            }
        )

    cut = workday_date(spec.start, site.status_days - 1)
    return {
        "semana_corte": site.status_week,
        "fecha_corte": cut.isoformat(),
        "dias_laborables_transcurridos": site.status_days,
        "retrasos_plantados": [
            {"actividad": d.activity, "dias": d.days, "causa": d.cause} for d in site.delays
        ],
        "desviaciones": deviations(cpm, site),
        "impacto_fin_obra_dias": project_impact(spec.durations, spec.links, site, cpm),
        "partes": partes,
    }


def write_tender(
    spec: Spec, root: Path, with_injection: bool, twin_of: str | None, twin: str | None
) -> None:
    tid = spec.id + ("-gemelo" if twin_of else "")
    out = root / "tenders" / tid
    docs: dict[str, tuple[Document, Rendered]] = {}
    for name, builder in BUILDERS.items():
        doc = builder(spec, with_injection)
        rend = render(doc, f"{doc.title} - {spec.id}")
        out.mkdir(parents=True, exist_ok=True)
        (out / f"{name}.pdf").write_bytes(rend.pdf)
        _write_text(out / f"{name}.txt", rend.text())
        docs[name] = (doc, rend)
    site_extra = _site_files(spec, out)
    _write_json(
        root / "ground_truth" / f"{tid}.json", ground_truth(spec, docs, twin_of, twin, site_extra)
    )


def _tarifas() -> dict[str, object]:
    return {
        "empresa": CONTRACTOR,
        "nota": "Tarifas propias ficticias. Precio por unidad canónica, IVA excluido. "
        "Rendimiento por cuadrilla y día laborable.",
        "partidas": [
            {
                "codigo": w.code,
                "descripcion": w.description,
                "actividad": w.activity,
                "unidad": w.unit,
                "precio_unitario_eur": str(w.unit_price),
                "rendimiento_por_dia": str(w.rate_per_day),
            }
            for w in WORK_ITEMS
        ],
    }


def generate(seed: int, split: str, out: Path) -> dict[str, object]:
    if split not in SPLIT_PREFIX:
        raise ValueError("split must be dev or eval")
    out.mkdir(parents=True, exist_ok=True)
    if any(out.iterdir()):
        raise FileExistsError(f"output directory is not empty: {out}")
    r = stream(seed, split, "split")
    sizes = [s for s in SIZES for _ in range(TENDERS_PER_SPLIT // len(SIZES))]
    r.shuffle(sizes)
    idx = list(range(TENDERS_PER_SPLIT))
    flags: dict[int, set[str]] = {i: set() for i in idx}
    for name, count in (
        ("injection", N_INJECTION),
        ("contradiction", N_CONTRADICTION),
        ("duplicate", N_DUPLICATE),
        ("tight", N_TIGHT),
    ):
        for i in r.sample(idx, count):
            flags[i].add(name)

    _write_json(out / "company" / "tarifas.json", _tarifas())
    for i in idx:
        spec = build_spec(seed, split, i, sizes[i], flags[i])
        if spec.injection is not None:
            write_tender(spec, out, True, None, spec.id + "-gemelo")
            write_tender(spec, out, False, spec.id, None)
        else:
            write_tender(spec, out, True, None, None)

    files = sorted(p for p in out.rglob("*") if p.is_file())
    manifest = {
        "semilla": seed,
        "conjunto": split,
        "version_generador": __version__,
        "ficheros": {
            p.relative_to(out).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in files
        },
    }
    _write_json(out / "manifest.json", manifest)
    return manifest
