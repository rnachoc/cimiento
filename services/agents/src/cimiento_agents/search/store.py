"""Chunk and vector stores: pgvector for the service, in-memory for fast evaluation.

Both return the same ranking for a query (cosine similarity on L2-normalised vectors);
a test checks it against a real Postgres.
"""

from __future__ import annotations

from typing import Protocol

import numpy as np

from ..ingest.chunking import Chunk


class Store(Protocol):
    def add(self, chunks: list[Chunk], vectors: np.ndarray) -> None: ...

    def chunks(self, tender: str) -> list[Chunk]: ...

    def vector_ranking(self, tender: str, query: np.ndarray) -> list[tuple[Chunk, float]]: ...

    def tenders(self) -> list[str]: ...


class InMemoryStore:
    def __init__(self) -> None:
        self._chunks: dict[str, list[Chunk]] = {}
        self._vectors: dict[str, np.ndarray] = {}

    def add(self, chunks: list[Chunk], vectors: np.ndarray) -> None:
        if len(chunks) != len(vectors):
            raise ValueError("one vector per chunk")
        for tender in sorted({c.tender for c in chunks}):
            idx = [i for i, c in enumerate(chunks) if c.tender == tender]
            self._chunks[tender] = self._chunks.get(tender, []) + [chunks[i] for i in idx]
            new = vectors[idx]
            old = self._vectors.get(tender)
            self._vectors[tender] = new if old is None else np.vstack([old, new])

    def chunks(self, tender: str) -> list[Chunk]:
        return list(self._chunks.get(tender, []))

    def vector_ranking(self, tender: str, query: np.ndarray) -> list[tuple[Chunk, float]]:
        chunks = self._chunks.get(tender, [])
        if not chunks:
            return []
        sims = self._vectors[tender] @ query
        order = sorted(range(len(chunks)), key=lambda i: (-float(sims[i]), chunks[i].id))
        return [(chunks[i], float(sims[i])) for i in order]

    def tenders(self) -> list[str]:
        return sorted(self._chunks)


class PgVectorStore:
    """Chunks and embeddings in Postgres with pgvector (the agents' own database)."""

    def __init__(self, dsn: str, dim: int) -> None:
        import psycopg

        self.dim = dim
        self.conn = psycopg.connect(dsn, autocommit=True)
        self.conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
        self.conn.execute(
            f"""CREATE TABLE IF NOT EXISTS search_chunk (
                id TEXT PRIMARY KEY, tender TEXT NOT NULL, document TEXT NOT NULL,
                page INT NOT NULL, heading TEXT NOT NULL, text TEXT NOT NULL,
                embedding vector({dim}) NOT NULL)"""
        )
        self.conn.execute("CREATE INDEX IF NOT EXISTS search_chunk_tender ON search_chunk (tender)")

    @staticmethod
    def _vec(v: np.ndarray) -> str:
        return "[" + ",".join(f"{x:.7f}" for x in v.tolist()) + "]"

    def add(self, chunks: list[Chunk], vectors: np.ndarray) -> None:
        if len(chunks) != len(vectors):
            raise ValueError("one vector per chunk")
        with self.conn.cursor() as cur:
            for c, v in zip(chunks, vectors, strict=True):
                cur.execute(
                    """INSERT INTO search_chunk (id, tender, document, page, heading, text, embedding)
                       VALUES (%s, %s, %s, %s, %s, %s, %s::vector)
                       ON CONFLICT (id) DO UPDATE SET text = EXCLUDED.text, heading = EXCLUDED.heading,
                         embedding = EXCLUDED.embedding""",
                    (c.id, c.tender, c.document, c.page, c.heading, c.text, self._vec(v)),
                )

    def _rows_to_chunks(self, rows) -> list[Chunk]:
        return [Chunk(*r[:6]) for r in rows]

    def chunks(self, tender: str) -> list[Chunk]:
        rows = self.conn.execute(
            "SELECT id, tender, document, page, heading, text FROM search_chunk WHERE tender = %s "
            "ORDER BY document, page, id",
            (tender,),
        ).fetchall()
        return self._rows_to_chunks(rows)

    def vector_ranking(self, tender: str, query: np.ndarray) -> list[tuple[Chunk, float]]:
        rows = self.conn.execute(
            "SELECT id, tender, document, page, heading, text, 1 - (embedding <=> %s::vector) AS sim "
            "FROM search_chunk WHERE tender = %s ORDER BY embedding <=> %s::vector, id",
            (self._vec(query), tender, self._vec(query)),
        ).fetchall()
        return [(Chunk(*r[:6]), float(r[6])) for r in rows]

    def tenders(self) -> list[str]:
        rows = self.conn.execute(
            "SELECT DISTINCT tender FROM search_chunk ORDER BY tender"
        ).fetchall()
        return [r[0] for r in rows]
