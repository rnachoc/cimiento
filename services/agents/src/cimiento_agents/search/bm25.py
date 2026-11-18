"""Okapi BM25 over a small in-memory corpus (one tender has tens of chunks)."""

from __future__ import annotations

import math
from collections import Counter


class BM25:
    def __init__(self, docs: list[list[str]], k1: float = 1.5, b: float = 0.75) -> None:
        self.k1, self.b = k1, b
        self.tf = [Counter(d) for d in docs]
        self.len = [len(d) for d in docs]
        self.avg = (sum(self.len) / len(docs)) if docs else 0.0
        df: Counter[str] = Counter()
        for d in docs:
            df.update(set(d))
        n = len(docs)
        self.idf = {t: math.log(1 + (n - f + 0.5) / (f + 0.5)) for t, f in df.items()}

    def scores(self, query: list[str]) -> list[float]:
        out = []
        for tf, length in zip(self.tf, self.len, strict=True):
            s = 0.0
            for t in query:
                f = tf.get(t, 0)
                if f:
                    norm = (
                        self.k1 * (1 - self.b + self.b * length / self.avg) if self.avg else self.k1
                    )
                    s += self.idf[t] * f * (self.k1 + 1) / (f + norm)
            out.append(s)
        return out
