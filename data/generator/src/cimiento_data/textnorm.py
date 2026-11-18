"""Text normalisation and Spanish number formatting/parsing.

`normalize` is the single definition of "same text" used by citation checks:
NFKC, lower case, soft hyphens removed, whitespace collapsed.
"""

from __future__ import annotations

import re
import unicodedata
from decimal import ROUND_HALF_UP, Decimal

_WS = re.compile(r"\s+")


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).replace("­", "")
    return _WS.sub(" ", text).strip().lower()


def q2(value: Decimal) -> Decimal:
    """Round to cents (half up)."""
    return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def _group_thousands(int_part: str, sep: str) -> str:
    out = []
    for i, ch in enumerate(reversed(int_part)):
        if i and i % 3 == 0:
            out.append(sep)
        out.append(ch)
    return "".join(reversed(out))


def format_es(value: Decimal, decimals: int, style: str = "dots") -> str:
    """Format a non-negative number the Spanish way.

    styles: "dots" 1.234,50 | "plain" 1234,50 | "spaces" 1 234,50 | "trim" 1234,5 (trailing
    zeros removed, no grouping).
    """
    if value < 0:
        raise ValueError("negative values are not used")
    quant = Decimal(1).scaleb(-decimals) if decimals else Decimal(1)
    text = f"{value.quantize(quant, rounding=ROUND_HALF_UP):f}"
    int_part, _, frac = text.partition(".")
    if style == "trim":
        frac = frac.rstrip("0")
        return int_part + ("," + frac if frac else "")
    sep = {"dots": ".", "plain": "", "spaces": " "}[style]
    grouped = _group_thousands(int_part, sep) if sep else int_part
    return grouped + ("," + frac if frac else "")


_NUM = re.compile(r"^\d{1,3}(?:([. ])\d{3})*(?:,\d+)?$|^\d+(?:,\d+)?$")


def parse_es(text: str) -> Decimal:
    """Parse a Spanish formatted number ("1.234,50", "1234,5", "1 234,50")."""
    text = text.strip()
    if not _NUM.match(text):
        raise ValueError(f"not a Spanish number: {text!r}")
    return Decimal(text.replace(".", "").replace(" ", "").replace(",", "."))
