"""Static catalogue: work items, company rates, activities, dependency rules, names.

Everything here is fictional. Rates are Constructora Brisa's own (invented) prices
and productivities.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal

CONTRACTOR = "Constructora Brisa"


@dataclass(frozen=True)
class WorkItem:
    code: str
    description: str
    activity: str
    unit: str  # canonical unit: m3, m2, m, ud
    rate_per_day: Decimal  # canonical units per crew and working day
    unit_price: Decimal  # EUR per canonical unit, VAT excluded
    base_quantity: Decimal  # quantity for a medium tender, scaled by size


WORK_ITEMS: tuple[WorkItem, ...] = (
    WorkItem(
        "MT01",
        "Excavación en zanjas y pozos",
        "A01",
        "m3",
        Decimal("60"),
        Decimal("18.50"),
        Decimal("900"),
    ),
    WorkItem(
        "MT02",
        "Relleno y compactación de tierras",
        "A01",
        "m3",
        Decimal("80"),
        Decimal("12.30"),
        Decimal("600"),
    ),
    WorkItem(
        "CI01",
        "Hormigón de limpieza HL-150",
        "A02",
        "m3",
        Decimal("25"),
        Decimal("85.00"),
        Decimal("60"),
    ),
    WorkItem(
        "CI02",
        "Hormigón armado HA-25 en zapatas",
        "A02",
        "m3",
        Decimal("15"),
        Decimal("160.00"),
        Decimal("240"),
    ),
    WorkItem(
        "ES01",
        "Hormigón armado en pilares",
        "A03",
        "m3",
        Decimal("6"),
        Decimal("420.00"),
        Decimal("90"),
    ),
    WorkItem(
        "ES02", "Forjado reticular", "A03", "m2", Decimal("40"), Decimal("65.00"), Decimal("2400")
    ),
    WorkItem(
        "CE01",
        "Fábrica de ladrillo cara vista",
        "A04",
        "m2",
        Decimal("25"),
        Decimal("38.00"),
        Decimal("1500"),
    ),
    WorkItem(
        "CU01",
        "Cubierta plana invertida",
        "A05",
        "m2",
        Decimal("30"),
        Decimal("72.00"),
        Decimal("800"),
    ),
    WorkItem(
        "CA01",
        "Carpintería exterior de aluminio",
        "A06",
        "ud",
        Decimal("8"),
        Decimal("310.00"),
        Decimal("120"),
    ),
    WorkItem(
        "IS01",
        "Red de saneamiento enterrada",
        "A07",
        "m",
        Decimal("40"),
        Decimal("45.00"),
        Decimal("600"),
    ),
    WorkItem(
        "IE01",
        "Punto de luz completo",
        "A08",
        "ud",
        Decimal("12"),
        Decimal("55.00"),
        Decimal("400"),
    ),
    WorkItem(
        "IF01",
        "Punto de agua de fontanería",
        "A09",
        "ud",
        Decimal("6"),
        Decimal("95.00"),
        Decimal("150"),
    ),
    WorkItem(
        "AC01",
        "Solado de gres porcelánico",
        "A10",
        "m2",
        Decimal("30"),
        Decimal("32.00"),
        Decimal("2000"),
    ),
    WorkItem(
        "AC02",
        "Pintura plástica en paramentos",
        "A11",
        "m2",
        Decimal("120"),
        Decimal("6.50"),
        Decimal("5000"),
    ),
    WorkItem(
        "UR01",
        "Pavimento de urbanización exterior",
        "A12",
        "m2",
        Decimal("70"),
        Decimal("28.00"),
        Decimal("1800"),
    ),
)

WORK_ITEM_BY_CODE = {w.code: w for w in WORK_ITEMS}

ACTIVITIES: dict[str, str] = {
    "A01": "Movimiento de tierras",
    "A02": "Cimentación",
    "A03": "Estructura",
    "A04": "Cerramientos",
    "A05": "Cubierta",
    "A06": "Carpintería exterior",
    "A07": "Saneamiento",
    "A08": "Instalación eléctrica",
    "A09": "Fontanería",
    "A10": "Solados",
    "A11": "Pintura",
    "A12": "Urbanización",
}

# Typical dependency rules: (predecessor, successor, lag in working days), finish-to-start.
DEPENDENCY_RULES: tuple[tuple[str, str, int], ...] = (
    ("A01", "A02", 0),
    ("A01", "A07", 0),
    ("A02", "A03", 3),
    ("A03", "A04", 0),
    ("A03", "A05", 0),
    ("A03", "A12", 0),
    ("A04", "A06", 0),
    ("A04", "A08", 0),
    ("A04", "A09", 0),
    ("A07", "A12", 0),
    ("A08", "A10", 0),
    ("A09", "A10", 0),
    ("A06", "A11", 2),
    ("A10", "A11", 0),
)

# Activities per tender size (always a subset that keeps A01 as the only start).
SIZE_ACTIVITIES: dict[str, tuple[str, ...]] = {
    "pequeña": ("A01", "A02", "A03", "A04", "A05", "A08", "A11"),
    "mediana": ("A01", "A02", "A03", "A04", "A05", "A06", "A07", "A08", "A10", "A11"),
    "grande": tuple(ACTIVITIES),
}
SIZE_SCALE: dict[str, Decimal] = {
    "pequeña": Decimal("0.4"),
    "mediana": Decimal("1.0"),
    "grande": Decimal("2.2"),
}
SIZE_CREWS: dict[str, int] = {"pequeña": 1, "mediana": 2, "grande": 3}

# Unit spellings found in measurement tables -> (canonical unit, factor to canonical).
UNIT_SPELLINGS: dict[str, tuple[str, Decimal]] = {
    "m3": ("m3", Decimal(1)),
    "m³": ("m3", Decimal(1)),
    "M3": ("m3", Decimal(1)),
    "m2": ("m2", Decimal(1)),
    "m²": ("m2", Decimal(1)),
    "M2": ("m2", Decimal(1)),
    "ha": ("m2", Decimal(10000)),
    "m": ("m", Decimal(1)),
    "ml": ("m", Decimal(1)),
    "m.l.": ("m", Decimal(1)),
    "km": ("m", Decimal(1000)),
    "ud": ("ud", Decimal(1)),
    "u": ("ud", Decimal(1)),
    "uds": ("ud", Decimal(1)),
}
# Canonical unit -> spellings used as noise (same factor 1).
UNIT_NOISE: dict[str, tuple[str, ...]] = {
    "m3": ("m3", "m³", "M3"),
    "m2": ("m2", "m²", "M2"),
    "m": ("m", "ml", "m.l."),
    "ud": ("ud", "u", "uds"),
}
# Work items that may carry a row in a larger unit (mixed units).
MIXED_UNIT: dict[str, str] = {"IS01": "km", "UR01": "ha"}

LOCATIONS = ("Bloque A", "Bloque B", "Zona exterior", "Planta baja", "Planta primera", "Sótano")

# Invented place names. Contracting authorities are derived from them.
TOWNS = (
    "Castrolumen",
    "Valcéfiro",
    "Puerto Lúmina",
    "Sotoclaro",
    "Fuentecéfiro",
    "Lumbralta",
    "Arenalia",
    "Torrebrisa del Monte",
    "Villaescarcha",
    "Robledal de Ámbar",
)
AUTHORITIES = (
    "Ayuntamiento de {town}",
    "Consorcio Público de Vivienda de {town}",
    "Mancomunidad de Servicios de {town}",
)
BUILDINGS = (
    "centro cívico",
    "edificio de viviendas de protección pública",
    "escuela infantil",
    "pabellón polideportivo",
    "biblioteca municipal",
    "centro de salud",
)

# Questions whose answer is in no document. `absent` terms must never appear in any
# generated text (checked by tests).
UNANSWERABLE: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("¿Se admite la revisión de precios en este contrato?", ("revisión de precios",)),
    ("¿Qué horario de trabajo se permite en la obra?", ("horario",)),
    ("¿Se exige certificación ISO 14001 a la empresa licitadora?", ("iso",)),
    ("¿Cuál es el plazo de garantía de las obras una vez recibidas?", ("plazo de garantía",)),
    ("¿Es obligatorio entregar el proyecto en formato BIM?", ("bim",)),
    (
        "¿Qué seguro de responsabilidad civil se exige y por qué importe?",
        ("responsabilidad civil",),
    ),
)
