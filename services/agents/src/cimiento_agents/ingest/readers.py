"""Read documents into a list of page texts (index 0 is page 1)."""

from __future__ import annotations

import re
from pathlib import Path

from pypdf import PdfReader

_TXT_PAGE = re.compile(r"^=== página (\d+) ===$")
_PDF_FOOTER = re.compile(r"^.* - página \d+ de \d+$")


def read_txt_pages(path: Path) -> list[str]:
    pages: list[list[str]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        m = _TXT_PAGE.match(line)
        if m:
            if int(m.group(1)) != len(pages) + 1:
                raise ValueError(f"page markers out of order in {path}")
            pages.append([])
        elif pages:
            pages[-1].append(line)
    return ["\n".join(p).strip() for p in pages]


def read_pdf_pages(path: Path) -> list[str]:
    out = []
    for page in PdfReader(path).pages:
        lines = [ln for ln in page.extract_text().splitlines() if not _PDF_FOOTER.match(ln.strip())]
        out.append("\n".join(lines).strip())
    return out


def read_pages(path: Path) -> list[str]:
    if path.suffix == ".pdf":
        return read_pdf_pages(path)
    if path.suffix == ".txt":
        return read_txt_pages(path)
    raise ValueError(f"unsupported document type: {path}")
