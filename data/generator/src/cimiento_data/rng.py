"""Deterministic random streams derived from (seed, split, key).

Each tender gets its own stream, so adding a tender or changing the order of
generation does not change the others.
"""

from __future__ import annotations

import hashlib
import random


def stream(seed: int, split: str, key: str) -> random.Random:
    digest = hashlib.sha256(f"{seed}:{split}:{key}".encode()).digest()
    return random.Random(int.from_bytes(digest[:8], "big"))
