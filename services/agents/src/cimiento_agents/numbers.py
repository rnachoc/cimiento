"""Spanish numbers: "1.234,50" style and number words up to 99."""

from __future__ import annotations

import re
from decimal import Decimal

_NUM = re.compile(r"^\d{1,3}(?:[. ]\d{3})*(?:,\d+)?$|^\d+(?:,\d+)?$")

_UNITS = [
    "cero",
    "uno",
    "dos",
    "tres",
    "cuatro",
    "cinco",
    "seis",
    "siete",
    "ocho",
    "nueve",
    "diez",
    "once",
    "doce",
    "trece",
    "catorce",
    "quince",
    "dieciséis",
    "diecisiete",
    "dieciocho",
    "diecinueve",
    "veinte",
    "veintiuno",
    "veintidós",
    "veintitrés",
    "veinticuatro",
    "veinticinco",
    "veintiséis",
    "veintisiete",
    "veintiocho",
    "veintinueve",
]
_TENS = {
    "treinta": 30,
    "cuarenta": 40,
    "cincuenta": 50,
    "sesenta": 60,
    "setenta": 70,
    "ochenta": 80,
    "noventa": 90,
}
_WORDS: dict[str, int] = {w: i for i, w in enumerate(_UNITS)}
_WORDS.update({"un": 1, "una": 1, "veintiún": 21, "cien": 100})
_WORDS.update(_TENS)


def parse_es_number(text: str) -> Decimal:
    text = text.strip()
    if not _NUM.match(text):
        raise ValueError(f"not a Spanish number: {text!r}")
    return Decimal(text.replace(".", "").replace(" ", "").replace(",", "."))


def parse_es_words(text: str) -> int | None:
    """'doce' -> 12, 'treinta y un' -> 31; None when not a number word."""
    text = text.strip().lower()
    if text in _WORDS:
        return _WORDS[text]
    parts = text.split(" y ")
    if len(parts) == 2 and parts[0] in _TENS and parts[1] in _WORDS and _WORDS[parts[1]] < 10:
        return _TENS[parts[0]] + _WORDS[parts[1]]
    return None
