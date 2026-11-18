"""Rule-based detector of instructions planted in documents.

Runs on every document regardless of the model, so detection does not depend on the model
noticing. It flags sentences addressed to an assistant or system, or asking to skip checks,
approve, or change results. Flagged text is reported with its citation and never acted on.
"""

from __future__ import annotations

import re

from .citations import Citation, TenderDocs

# Written from the dev split and general knowledge of prompt injection only. The eval split
# holds instruction wordings that were never used to write these patterns.
PATTERNS = [
    r"\bignor(a|e|ad|en)\b.{0,40}\b(instrucciones|instructions|anterior|prior|previous)",
    r"\b(asistente|assistant|modelo de lenguaje|language model|sistema de análisis)\b",
    r"^\s*system\s*:",
    r"\bsin (más )?(revisión|comprobaciones)\b",
    r"\b(aprobada|approved)\b.{0,40}\b(directamente|sin)\b",
    r"\bmarca esta licitación\b",
]
_RX = [re.compile(p, re.IGNORECASE) for p in PATTERNS]


def detect(docs: TenderDocs) -> list[Citation]:
    found: list[Citation] = []
    for document, pages in docs.pages.items():
        for page_no, page in enumerate(pages, start=1):
            for line in page.splitlines():
                text = line.strip()
                if text and any(rx.search(text) for rx in _RX):
                    found.append(Citation(document=document, page=page_no, text=text))
    return found
