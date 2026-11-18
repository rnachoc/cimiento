"""HTTP API of the agents service. Endpoints stay thin; logic lives in the pipeline."""

from __future__ import annotations

import json
import os
from collections.abc import Callable
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .search.pipeline import METHODS, SearchPipeline


class SearchRequest(BaseModel):
    tender: str = Field(min_length=1, max_length=64)
    query: str = Field(min_length=1, max_length=1000)
    k: int = Field(default=5, ge=1, le=50)
    method: Literal["lexical", "vector", "hybrid", "rerank"] = "rerank"


class StageRanks(BaseModel):
    lexical: int | None
    vector: int | None
    hybrid: int | None
    rerank: int | None


class SearchHit(BaseModel):
    chunk_id: str
    document: str
    page: int
    heading: str
    text: str
    score: float
    ranks: StageRanks


class SearchResponse(BaseModel):
    tender: str
    method: str
    no_consta: bool
    top_score: float | None
    results: list[SearchHit]


def create_app(pipeline_factory: Callable[[], SearchPipeline]) -> FastAPI:
    app = FastAPI(title="Cimiento agents", version="0.1.0")
    state: dict[str, SearchPipeline] = {}

    def pipeline() -> SearchPipeline:
        if "p" not in state:
            state["p"] = pipeline_factory()
        return state["p"]

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/search", response_model=SearchResponse)
    def search(req: SearchRequest) -> SearchResponse:
        p = pipeline()
        if req.method == "rerank" and p.reranker is None:
            raise HTTPException(422, "rerank is not available in this deployment")
        if not p.store.chunks(req.tender):
            raise HTTPException(404, f"tender {req.tender} is not indexed")
        result = p.search(req.tender, req.query, req.k, req.method)
        return SearchResponse(
            tender=req.tender,
            method=result.method,
            no_consta=result.no_consta,
            top_score=result.top_score,
            results=[]
            if result.no_consta
            else [
                SearchHit(
                    chunk_id=h.chunk.id,
                    document=h.chunk.document,
                    page=h.chunk.page,
                    heading=h.chunk.heading,
                    text=h.chunk.text,
                    score=h.score,
                    ranks=StageRanks(**h.ranks),
                )
                for h in result.hits
            ],
        )

    return app


def default_pipeline() -> SearchPipeline:
    """Service configuration from the environment: pgvector store and local models."""
    from .search.models import FastEmbedder, FastReranker
    from .search.store import PgVectorStore

    dsn = os.environ["CIMIENTO_SEARCH_DSN"]
    embedder = FastEmbedder()
    reranker = FastReranker()
    thresholds: dict[str, float] = {}
    path = os.environ.get("CIMIENTO_SEARCH_THRESHOLDS")
    if path:
        thresholds = {
            k: float(v) for k, v in json.loads(Path(path).read_text()).items() if k in METHODS
        }
    return SearchPipeline(
        PgVectorStore(dsn, embedder.dim), embedder, reranker, thresholds=thresholds
    )


def app_from_env() -> FastAPI:
    return create_app(default_pipeline)
