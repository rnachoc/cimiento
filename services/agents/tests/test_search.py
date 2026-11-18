from __future__ import annotations

import numpy as np
import pytest
from fastapi.testclient import TestClient

from cimiento_agents.api import create_app
from cimiento_agents.ingest.chunking import chunk_pages, chunk_tender
from cimiento_agents.search.bm25 import BM25
from cimiento_agents.search.models import HashEmbedder, OverlapReranker
from cimiento_agents.search.pipeline import SearchPipeline
from cimiento_agents.search.store import InMemoryStore, PgVectorStore
from cimiento_agents.textnorm import normalize, tokens

PAGES = [
    "PLIEGO\nExpediente T-1.\nCláusula 1. Objeto del contrato\nObras de un centro cívico.\n"
    "Cláusula 2. Plazo de ejecución\nEl plazo de ejecución será de doce (12) meses.",
    "3. PENALIDADES POR DEMORA\nSe impondrán 0,60 euros por cada 1.000 euros por día de demora.\n"
    "4) Garantías\nGarantía definitiva del 5 % del importe.",
]


def make_pipeline(**kw) -> SearchPipeline:
    store = InMemoryStore()
    chunks = chunk_pages("T-1", "pliego", PAGES)
    emb = HashEmbedder()
    store.add(chunks, emb.embed([c.text for c in chunks]))
    return SearchPipeline(store, emb, OverlapReranker(), **kw)


def test_chunks_follow_clauses_and_pages():
    chunks = chunk_pages("T-1", "pliego", PAGES)
    assert [(c.page, c.heading) for c in chunks] == [
        (1, ""),
        (1, "Cláusula 1. Objeto del contrato"),
        (1, "Cláusula 2. Plazo de ejecución"),
        (2, "3. PENALIDADES POR DEMORA"),
        (2, "4) Garantías"),
    ]
    assert chunks[2].text.endswith("doce (12) meses.")
    assert len({c.id for c in chunks}) == len(chunks)


def test_wrapped_number_lines_are_not_headings():
    chunks = chunk_pages(
        "T-1", "pliego", ["Cláusula 1. Penalidades\nSe impondrán\n1.000 euros por día"]
    )
    assert len(chunks) == 1


def test_tokens_fold_accents_and_drop_stopwords():
    assert tokens("¿Cuál es el plazo de EJECUCIÓN de las obras?") == ["plazo", "ejecucion", "obra"]
    assert normalize("  A\n  b ") == "a b"


def test_bm25_prefers_matching_document():
    bm = BM25([tokens("plazo de ejecución doce meses"), tokens("garantía definitiva")])
    s = bm.scores(tokens("plazo de ejecución"))
    assert s[0] > s[1] == 0.0
    assert BM25([]).scores(["x"]) == []


@pytest.mark.parametrize("method", ["lexical", "vector", "hybrid", "rerank"])
def test_every_method_finds_the_clause_and_reports_stage_ranks(method):
    res = make_pipeline().search("T-1", "¿Cuál es el plazo de ejecución?", k=3, method=method)
    assert res.hits[0].chunk.heading == "Cláusula 2. Plazo de ejecución"
    assert len(res.hits) == 3
    ranks = res.hits[0].ranks
    assert ranks["lexical"] and ranks["vector"] and ranks["hybrid"]
    assert (ranks["rerank"] is not None) == (method == "rerank")


def test_no_consta_below_threshold():
    p = make_pipeline(thresholds={"rerank": 0.5})
    assert p.search("T-1", "¿Qué horario de trabajo se permite?", method="rerank").no_consta
    assert not p.search("T-1", "plazo de ejecución meses", method="rerank").no_consta


def test_search_validates_input():
    p = make_pipeline()
    with pytest.raises(ValueError):
        p.search("T-1", "x", method="magic")
    with pytest.raises(ValueError):
        p.search("T-1", "   ")
    assert make_pipeline().search("NOPE", "plazo", method="lexical").no_consta


def test_api():
    client = TestClient(create_app(lambda: make_pipeline(thresholds={"rerank": 0.5})))
    ok = client.post("/search", json={"tender": "T-1", "query": "plazo de ejecución", "k": 2})
    assert ok.status_code == 200
    body = ok.json()
    assert body["no_consta"] is False and len(body["results"]) == 2
    first = body["results"][0]
    assert (first["document"], first["page"]) == ("pliego", 1)
    assert set(first["ranks"]) == {"lexical", "vector", "hybrid", "rerank"}

    none = client.post("/search", json={"tender": "T-1", "query": "horario de trabajo permitido"})
    assert none.json()["no_consta"] is True and none.json()["results"] == []
    assert client.post("/search", json={"tender": "X", "query": "plazo"}).status_code == 404
    assert (
        client.post("/search", json={"tender": "T-1", "query": "plazo", "method": "x"}).status_code
        == 422
    )
    assert client.post("/search", json={"tender": "T-1", "query": ""}).status_code == 422
    assert client.post("/search", json={"tender": "T-1", "query": "a", "k": 0}).status_code == 422


def test_pdf_and_text_ingestion_give_the_same_chunks(dev_split):
    for folder in sorted((dev_split / "tenders").iterdir())[:6]:
        a, b = chunk_tender(folder, "pdf"), chunk_tender(folder, "txt")
        assert [(c.id, c.page, c.heading) for c in a] == [(c.id, c.page, c.heading) for c in b]
        assert [normalize(c.text) for c in a] == [normalize(c.text) for c in b]


def test_pgvector_ranking_matches_in_memory(pg_dsn):
    chunks = chunk_pages("T-1", "pliego", PAGES) + chunk_pages("T-2", "pliego", PAGES[:1])
    emb = HashEmbedder()
    vecs = emb.embed([c.text for c in chunks])
    mem = InMemoryStore()
    mem.add(chunks, vecs)
    pg = PgVectorStore(pg_dsn, emb.dim)
    pg.add(chunks, vecs)
    pg.add(chunks, vecs)  # idempotent re-ingestion
    assert pg.tenders() == ["T-1", "T-2"]
    assert sorted(c.id for c in pg.chunks("T-1")) == sorted(c.id for c in mem.chunks("T-1"))
    for q in ["plazo de ejecución", "garantía definitiva", "penalidades demora"]:
        qv = emb.embed([q])[0]
        a = [(c.id, round(s, 5)) for c, s in mem.vector_ranking("T-1", qv)]
        b = [(c.id, round(s, 5)) for c, s in pg.vector_ranking("T-1", qv)]
        assert a == b
    assert np.isclose(
        pg.vector_ranking("T-1", emb.embed(["plazo"])[0])[0][1],
        mem.vector_ranking("T-1", emb.embed(["plazo"])[0])[0][1],
        atol=1e-5,
    )
