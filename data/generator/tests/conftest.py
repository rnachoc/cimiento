from __future__ import annotations

import io
import json
from functools import cache
from pathlib import Path

import pytest
from pypdf import PdfReader

from cimiento_data.generate import generate

SEEDS = {"dev": 42, "eval": 1337}


@pytest.fixture(scope="session")
def splits(tmp_path_factory) -> dict[str, Path]:
    out = {}
    for split, seed in SEEDS.items():
        root = tmp_path_factory.mktemp(split)
        generate(seed, split, root)
        out[split] = root
    return out


@pytest.fixture(scope="session")
def truths(splits) -> dict[str, dict[str, dict]]:
    return {
        split: {
            p.stem: json.loads(p.read_text(encoding="utf-8"))
            for p in sorted((root / "ground_truth").glob("*.json"))
        }
        for split, root in splits.items()
    }


def txt_pages(path: Path) -> list[str]:
    """Split a rendered .txt into pages using the page markers."""
    pages: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("=== página ") and line.endswith(" ==="):
            assert int(line.split()[2]) == len(pages) + 1
            pages.append("")
        else:
            pages[-1] += line + "\n"
    return pages


@cache
def pdf_pages(path: Path) -> list[str]:
    return [p.extract_text() for p in PdfReader(io.BytesIO(path.read_bytes())).pages]
