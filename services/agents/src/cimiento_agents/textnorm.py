"""Text normalisation shared by ingestion, search and citation checks."""

from __future__ import annotations

import re
import unicodedata

_WS = re.compile(r"\s+")
_TOKEN = re.compile(r"[a-z0-9]+")

# Short Spanish stop list: function words that carry no meaning for retrieval.
STOPWORDS = frozenset(
    """a al ante con contra de del desde el en entre es esta este hay la las lo los o para
    por que se sin sobre su sus un una y cual cuales cuanto cuanta cuantos cuantas como
    """.split()
)


def normalize(text: str) -> str:
    """Canonical form for comparing text: NFKC, lower case, no soft hyphens, single spaces."""
    text = unicodedata.normalize("NFKC", text).replace("­", "")
    return _WS.sub(" ", text).strip().lower()


def fold(text: str) -> str:
    """Lower case without accents (for lexical matching)."""
    decomposed = unicodedata.normalize("NFKD", text.lower())
    return "".join(c for c in decomposed if not unicodedata.combining(c))


def _stem(token: str) -> str:
    # Light plural stripping; enough to match "obras"/"obra" and "meses"/"mes".
    if len(token) > 4 and token.endswith("es"):
        return token[:-2]
    if len(token) > 3 and token.endswith("s"):
        return token[:-1]
    return token


def tokens(text: str) -> list[str]:
    return [_stem(t) for t in _TOKEN.findall(fold(text)) if t not in STOPWORDS]
