"""Lay out documents once and render them to PDF and to text with page numbers.

The PDF and the text come from the same layout, so a block is on the same page in
both. A unit (list of blocks) is never split across pages.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime

from fpdf import FPDF
from fpdf.enums import XPos, YPos

FIXED_DATE = datetime(2026, 1, 1, tzinfo=UTC)
PAGE_TOP = 20.0
PAGE_BOTTOM = 297.0 - 25.0
UNIT_GAP = 3.0

# kind -> (font style, size, line height, grey level or None for black)
STYLES: dict[str, tuple[str, float, float, int | None]] = {
    "title": ("B", 14, 8, None),
    "heading": ("B", 11, 6, None),
    "para": ("", 10.5, 5, None),
    "line": ("", 10, 5, None),
    "note": ("I", 8.5, 4, 90),
    "hidden": ("", 10, 5, 255),
}


@dataclass
class Block:
    kind: str
    text: str
    key: str | None = None


@dataclass
class Document:
    name: str  # pliego | anexo | mediciones
    title: str
    units: list[list[Block]] = field(default_factory=list)


@dataclass
class Rendered:
    pdf: bytes
    pages: list[list[str]]  # text blocks per page
    key_page: dict[str, int]  # block key -> 1-based page

    def text(self) -> str:
        parts = []
        for n, blocks in enumerate(self.pages, start=1):
            parts.append(f"=== página {n} ===\n" + "\n".join(blocks) + "\n")
        return "\n".join(parts)


def _set_style(pdf: FPDF, kind: str) -> float:
    style, size, lh, grey = STYLES[kind]
    pdf.set_font("Helvetica", style=style, size=size)
    g = 0 if grey is None else grey
    pdf.set_text_color(g, g, g)
    return lh


def _height(pdf: FPDF, block: Block) -> float:
    lh = _set_style(pdf, block.kind)
    lines = pdf.multi_cell(0, lh, block.text, align="L", dry_run=True, output="LINES")
    return len(lines) * lh


def render(doc: Document, footer: str) -> Rendered:
    pdf = FPDF(format="A4")
    pdf.set_creation_date(FIXED_DATE)
    pdf.set_creator("cimiento-data")
    pdf.set_producer("cimiento-data")
    pdf.set_author("Documento sintético")
    pdf.set_title(doc.title)
    pdf.set_auto_page_break(False)
    pdf.set_margins(20, PAGE_TOP, 20)

    pages: list[list[str]] = []
    key_page: dict[str, int] = {}

    def new_page() -> None:
        pdf.add_page()
        pages.append([])

    new_page()
    usable = PAGE_BOTTOM - PAGE_TOP
    for unit in doc.units:
        height = sum(_height(pdf, b) for b in unit)
        if height > usable:
            raise ValueError(f"unit too tall for one page in {doc.name}: {unit[0].text[:40]}")
        if pdf.get_y() + height > PAGE_BOTTOM:
            new_page()
        for block in unit:
            lh = _set_style(pdf, block.kind)
            pdf.multi_cell(0, lh, block.text, align="L", new_x=XPos.LMARGIN, new_y=YPos.NEXT)
            pages[-1].append(block.text)
            if block.key is not None:
                if block.key in key_page:
                    raise ValueError(f"duplicate block key {block.key}")
                key_page[block.key] = len(pages)
        pdf.set_y(pdf.get_y() + UNIT_GAP)

    for n in range(1, len(pages) + 1):
        pdf.page = n
        _set_style(pdf, "note")
        pdf.set_xy(20, 297 - 15)
        pdf.cell(0, 4, f"{footer} - página {n} de {len(pages)}", align="C")
    pdf.page = len(pages)

    return Rendered(bytes(pdf.output()), pages, key_page)
