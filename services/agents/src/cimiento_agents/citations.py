"""Citation checks: the cited text must be on the cited page of the cited document."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field

from .ingest.chunking import DOCUMENTS
from .ingest.readers import read_pages
from .textnorm import normalize


class Citation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    document: str = Field(description="pliego, anexo or mediciones")
    page: int = Field(description="1-based page number")
    text: str = Field(description="literal text copied from that page")


@dataclass
class TenderDocs:
    """Pages of one tender's documents, read from PDF."""

    tender: str
    pages: dict[str, list[str]]

    @classmethod
    def load(cls, folder: Path) -> TenderDocs:
        pages = {
            d: read_pages(folder / f"{d}.pdf") for d in DOCUMENTS if (folder / f"{d}.pdf").exists()
        }
        return cls(folder.name, pages)

    def verify(self, c: Citation) -> bool:
        pages = self.pages.get(c.document)
        if not pages or not 1 <= c.page <= len(pages):
            return False
        quote = normalize(c.text)
        return len(quote) >= 4 and quote in normalize(pages[c.page - 1])
