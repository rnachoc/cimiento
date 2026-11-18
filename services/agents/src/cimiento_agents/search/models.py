"""Embedding and reranking models.

Real models run locally through fastembed (ONNX, no GPU). The hashing embedder and the
overlap reranker are deterministic stand-ins for unit tests; they are never used to report
retrieval quality.
"""

from __future__ import annotations

import hashlib
from typing import Protocol

import numpy as np

from ..textnorm import tokens

EMBEDDING_MODEL = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
RERANK_MODEL = "BAAI/bge-reranker-v2-m3-int8"


class Embedder(Protocol):
    name: str
    dim: int

    def embed(self, texts: list[str]) -> np.ndarray: ...


class Reranker(Protocol):
    name: str

    def score(self, query: str, texts: list[str]) -> list[float]: ...


def _l2(m: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(m, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return m / norms


class FastEmbedder:
    def __init__(self, model: str = EMBEDDING_MODEL) -> None:
        from fastembed import TextEmbedding

        self.name = model
        self._model = TextEmbedding(model_name=model)
        self.dim = int(self.embed(["dimension probe"]).shape[1])

    def embed(self, texts: list[str]) -> np.ndarray:
        vecs = np.array(list(self._model.embed(texts)), dtype=np.float32)
        return _l2(vecs)


class FastReranker:
    def __init__(self, model: str = RERANK_MODEL) -> None:
        from fastembed.rerank.cross_encoder import TextCrossEncoder

        self.name = model
        self._model = TextCrossEncoder(model_name=model)

    def score(self, query: str, texts: list[str]) -> list[float]:
        return [float(s) for s in self._model.rerank(query, texts)]


class HashEmbedder:
    """Bag of hashed tokens. Deterministic, offline; for tests only."""

    name = "test-hash-embedder"

    def __init__(self, dim: int = 256) -> None:
        self.dim = dim

    def embed(self, texts: list[str]) -> np.ndarray:
        m = np.zeros((len(texts), self.dim), dtype=np.float32)
        for i, text in enumerate(texts):
            for t in tokens(text):
                h = int.from_bytes(hashlib.sha1(t.encode()).digest()[:4], "big")
                m[i, h % self.dim] += 1.0
        return _l2(m)


class OverlapReranker:
    """Share of query tokens present in the text. Deterministic; for tests only."""

    name = "test-overlap-reranker"

    def score(self, query: str, texts: list[str]) -> list[float]:
        q = set(tokens(query))
        return [len(q & set(tokens(t))) / len(q) if q else 0.0 for t in texts]
