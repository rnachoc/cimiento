"""Search pipeline with four methods and the rank of each result in every stage.

- lexical: BM25 over the tender's chunks.
- vector: cosine similarity of embeddings.
- hybrid: reciprocal rank fusion (RRF) of lexical and vector.
- rerank: cross-encoder over the top hybrid candidates.

`no_consta` is true when the top score of the chosen method is below its threshold
(thresholds are set from the dev split by the retrieval eval, never from eval).
"""

from __future__ import annotations

from dataclasses import dataclass, field

from ..ingest.chunking import Chunk
from ..textnorm import tokens
from .bm25 import BM25
from .models import Embedder, Reranker
from .store import Store

METHODS = ("lexical", "vector", "hybrid", "rerank")


@dataclass(frozen=True)
class Hit:
    chunk: Chunk
    score: float
    ranks: dict[str, int | None]


@dataclass(frozen=True)
class SearchResult:
    method: str
    hits: list[Hit]
    top_score: float | None
    no_consta: bool


@dataclass
class SearchPipeline:
    store: Store
    embedder: Embedder
    reranker: Reranker | None = None
    rerank_depth: int = 10
    rrf_k: int = 60
    thresholds: dict[str, float] = field(default_factory=dict)
    _bm25: dict[str, tuple[list[Chunk], BM25]] = field(default_factory=dict, repr=False)

    def _lexical(self, tender: str, query: str) -> list[tuple[Chunk, float]]:
        if tender not in self._bm25:
            chunks = self.store.chunks(tender)
            self._bm25[tender] = (chunks, BM25([tokens(c.text) for c in chunks]))
        chunks, bm25 = self._bm25[tender]
        scores = bm25.scores(tokens(query))
        order = sorted(range(len(chunks)), key=lambda i: (-scores[i], chunks[i].id))
        return [(chunks[i], scores[i]) for i in order]

    def search(self, tender: str, query: str, k: int = 5, method: str = "rerank") -> SearchResult:
        if method not in METHODS:
            raise ValueError(f"method must be one of {METHODS}")
        if method == "rerank" and self.reranker is None:
            raise ValueError("rerank needs a reranker")
        if not query.strip():
            raise ValueError("empty query")

        lexical = self._lexical(tender, query)
        lex_rank = {c.id: i + 1 for i, (c, _) in enumerate(lexical)}
        vector = self.store.vector_ranking(tender, self.embedder.embed([query])[0])
        vec_rank = {c.id: i + 1 for i, (c, _) in enumerate(vector)}
        by_id = {c.id: c for c, _ in lexical} | {c.id: c for c, _ in vector}

        fused = {
            cid: 1 / (self.rrf_k + lex_rank[cid]) + 1 / (self.rrf_k + vec_rank[cid])
            for cid in by_id
            if cid in lex_rank and cid in vec_rank
        }
        hybrid = sorted(fused, key=lambda cid: (-fused[cid], cid))
        hyb_rank = {cid: i + 1 for i, cid in enumerate(hybrid)}

        rer_rank: dict[str, int] = {}
        rer_score: dict[str, float] = {}
        if method == "rerank":
            candidates = hybrid[: self.rerank_depth]
            scores = self.reranker.score(query, [by_id[c].text for c in candidates])  # type: ignore[union-attr]
            rer_score = dict(zip(candidates, scores, strict=True))
            for i, cid in enumerate(sorted(candidates, key=lambda c: (-rer_score[c], c))):
                rer_rank[cid] = i + 1

        if method == "lexical":
            ranked = [(c.id, s) for c, s in lexical]
        elif method == "vector":
            ranked = [(c.id, s) for c, s in vector]
        elif method == "hybrid":
            ranked = [(cid, fused[cid]) for cid in hybrid]
        else:
            ranked = sorted(rer_score.items(), key=lambda kv: (-kv[1], kv[0]))

        hits = [
            Hit(
                by_id[cid],
                score,
                {
                    "lexical": lex_rank.get(cid),
                    "vector": vec_rank.get(cid),
                    "hybrid": hyb_rank.get(cid),
                    "rerank": rer_rank.get(cid),
                },
            )
            for cid, score in ranked[:k]
        ]
        top = ranked[0][1] if ranked else None
        threshold = self.thresholds.get(method)
        no_consta = top is None or (threshold is not None and top < threshold)
        return SearchResult(method, hits, top, no_consta)
