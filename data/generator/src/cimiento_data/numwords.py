"""Spanish number words for 0..999 (enough for months, percentages and years)."""

from __future__ import annotations

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
    3: "treinta",
    4: "cuarenta",
    5: "cincuenta",
    6: "sesenta",
    7: "setenta",
    8: "ochenta",
    9: "noventa",
}
_HUNDREDS = {
    1: "ciento",
    2: "doscientos",
    3: "trescientos",
    4: "cuatrocientos",
    5: "quinientos",
    6: "seiscientos",
    7: "setecientos",
    8: "ochocientos",
    9: "novecientos",
}


def _below_100(n: int) -> str:
    if n < 30:
        return _UNITS[n]
    tens, unit = divmod(n, 10)
    return _TENS[tens] + ("" if unit == 0 else " y " + _UNITS[unit])


def to_words(n: int, apocope: bool = False) -> str:
    """Spanish words for n. With apocope, "uno" ending becomes "un"/"ún" (before a
    masculine noun: "veintiún meses", "un mes")."""
    if not 0 <= n <= 999:
        raise ValueError("only 0..999 supported")
    if n == 100:
        words = "cien"
    elif n < 100:
        words = _below_100(n)
    else:
        hundreds, rest = divmod(n, 100)
        words = _HUNDREDS[hundreds] + ("" if rest == 0 else " " + _below_100(rest))
    if apocope:
        if words.endswith("veintiuno"):
            words = words[: -len("veintiuno")] + "veintiún"
        elif words.endswith("uno"):
            words = words[:-3] + "un"
    return words
