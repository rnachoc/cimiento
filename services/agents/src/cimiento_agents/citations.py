"""Citation checks: the cited text must be on the cited page of the cited document."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field

from .ingest.chunking import DOCUMENTS
from .ingest.readers import read_pages
from .textnorm import normalize

# Site records that proposals may cite (one page each). Kept apart from the tender documents
# so they never enter the bid analyst's prompt or the injection detector.
SITE_DOCUMENT = "obra/avance_semanal.csv"


class Citation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    document: str = Field(description="pliego, anexo or mediciones")
    page: int = Field(description="1-based page number")
    text: str = Field(description="literal text copied from that page")


@dataclass
class TenderDocs:
    """Pages of one tender's documents, read from PDF, plus citable site records."""

    tender: str
    pages: dict[str, list[str]]
    site_pages: dict[str, list[str]] = field(default_factory=dict)

    @classmethod
    def load(cls, folder: Path) -> TenderDocs:
        pages = {
            d: read_pages(folder / f"{d}.pdf") for d in DOCUMENTS if (folder / f"{d}.pdf").exists()
        }
        site: dict[str, list[str]] = {}
        if (folder / SITE_DOCUMENT).exists():
            site[SITE_DOCUMENT] = [(folder / SITE_DOCUMENT).read_text(encoding="utf-8")]
        return cls(folder.name, pages, site)

    def verify(self, c: Citation) -> bool:
        pages = self.pages.get(c.document) or self.site_pages.get(c.document)
        if not pages or not 1 <= c.page <= len(pages):
            return False
        quote = normalize(c.text)
        return len(quote) >= 4 and quote in normalize(pages[c.page - 1])
