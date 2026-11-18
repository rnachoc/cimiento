"""Split pages into chunks: one per clause (or chapter) and page."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from .readers import read_pages

DOCUMENTS = ("pliego", "anexo", "mediciones")

HEADING = re.compile(
    r"^(?:"
    r"(?:Cláusula|Artículo)\s+\d+[.)]?\s"  # Cláusula 3. / Artículo 3.
    r"|\d+[.)]\s+\S"  # 3. PLAZO / 3) Plazo / 1. Descripción
    r"|Capítulo\s+\d+\."  # Capítulo 2. Cimentación
    r"|Disposición adicional\b"
    r")"
)


@dataclass(frozen=True)
class Chunk:
    id: str
    tender: str
    document: str
    page: int
    heading: str
    text: str


def chunk_pages(tender: str, document: str, pages: list[str]) -> list[Chunk]:
    chunks: list[Chunk] = []
    for page_no, page in enumerate(pages, start=1):
        groups: list[tuple[str, list[str]]] = [("", [])]
        for line in page.splitlines():
            stripped = line.strip()
            if not stripped:
                continue
            if HEADING.match(stripped):
                groups.append((stripped, [stripped]))
            else:
                groups[-1][1].append(stripped)
        n = 0
        for heading, lines in groups:
            body = "\n".join(lines).strip()
            if body:
                cid = f"{tender}:{document}:{page_no}:{n}"
                chunks.append(Chunk(cid, tender, document, page_no, heading, body))
                n += 1
    return chunks


def chunk_tender(folder: Path, source: str = "pdf") -> list[Chunk]:
    """Chunks of every document of one tender folder, read from PDF (default) or text."""
    if source not in ("pdf", "txt"):
        raise ValueError("source must be pdf or txt")
    tender = folder.name
    out: list[Chunk] = []
    for doc in DOCUMENTS:
        path = folder / f"{doc}.{source}"
        if path.exists():
            out.extend(chunk_pages(tender, doc, read_pages(path)))
    return out
