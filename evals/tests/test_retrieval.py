"""Eval harness tests with deterministic stand-in models (no downloads)."""

from __future__ import annotations

from pathlib import Path

import pytest
from cimiento_agents.search.models import HashEmbedder, OverlapReranker

from cimiento_evals.questions import DEV_TEMPLATES, EVAL_TEMPLATES, questions_for_split
from cimiento_evals.retrieval import (
    build_pipeline,
    choose_method,
    hit_table,
    run_split,
    tune_threshold,
)

DATA = Path(__file__).resolve().parents[2] / "data" / "generated"


@pytest.fixture(scope="module")
def dev() -> Path:
    if not (DATA / "dev" / "manifest.json").exists():
        pytest.fail("synthetic data missing; run `make data`")
    return DATA / "dev"


def test_question_sets_do_not_share_wording():
    dev_q = {t for ts in DEV_TEMPLATES.values() for t in ts}
    eval_q = {t for ts in EVAL_TEMPLATES.values() for t in ts}
    assert dev_q.isdisjoint(eval_q)
    assert DEV_TEMPLATES.keys() == EVAL_TEMPLATES.keys()


def test_questions_cover_every_type(dev):
    qs = questions_for_split(dev, "dev")
    kinds = {q.kind for q in qs}
    assert kinds == {"campo", "anexo", "medicion", "sin_respuesta"}
    assert all(q.document and q.page and q.quote for q in qs if q.kind != "sin_respuesta")
    assert not any(t.endswith("-gemelo") for t in {q.tender for q in qs})


def test_harness_end_to_end_with_stand_in_models(dev):
    pipe = build_pipeline(dev, HashEmbedder(), OverlapReranker())
    outcomes, stats = run_split(dev, "dev", pipe)
    found, total = map(int, stats["answerable_with_quote_in_a_chunk"].split("/"))
    assert found == total  # every expected quote is inside one chunk after PDF ingestion
    table = hit_table(outcomes, ("lexical", "vector", "hybrid", "rerank"))
    assert table["lexical"]["all"]["n"] == total
    for m in table:
        assert 0 <= table[m]["all"]["hit@1"] <= table[m]["all"]["hit@5"] <= total
    assert choose_method(table) in table
    assert isinstance(tune_threshold(outcomes, "rerank"), float)
