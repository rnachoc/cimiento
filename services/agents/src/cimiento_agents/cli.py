"""Command line: index a split into pgvector, or serve the API."""

from __future__ import annotations

import argparse
import os
from pathlib import Path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="cimiento-agents")
    sub = parser.add_subparsers(dest="cmd", required=True)
    ingest = sub.add_parser("ingest", help="chunk, embed and store every tender of a split")
    ingest.add_argument("--split-dir", type=Path, required=True, help="e.g. data/generated/dev")
    ingest.add_argument("--source", choices=["pdf", "txt"], default="pdf")
    serve = sub.add_parser("serve", help="run the HTTP API")
    serve.add_argument("--port", type=int, default=8001)
    args = parser.parse_args(argv)

    if args.cmd == "ingest":
        from .ingest.chunking import chunk_tender
        from .search.models import FastEmbedder
        from .search.store import PgVectorStore

        embedder = FastEmbedder()
        store = PgVectorStore(os.environ["CIMIENTO_SEARCH_DSN"], embedder.dim)
        total = 0
        for folder in sorted((args.split_dir / "tenders").iterdir()):
            chunks = chunk_tender(folder, args.source)
            store.add(chunks, embedder.embed([c.text for c in chunks]))
            total += len(chunks)
        print(f"indexed {total} chunks from {args.split_dir}")
        return 0

    import uvicorn

    from .api import app_from_env

    uvicorn.run(app_from_env(), host="127.0.0.1", port=args.port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
