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
    worker = sub.add_parser("worker", help="analyse the tenders people upload in the console")
    worker.add_argument("--poll", type=float, default=3.0, help="seconds between queue checks")
    worker.add_argument("--once", action="store_true", help="stop when the queue is empty")
    args = parser.parse_args(argv)

    if args.cmd == "worker":
        from .core_client import CoreClient
        from .llm import model_from_env
        from .tender_agents import load_rates
        from .worker import IntakeClient
        from .worker import serve as serve_queue

        core = CoreClient(
            os.environ.get("CORE_URL", "http://localhost:8080"), os.environ["CIMIENTO_AGENT_KEY"]
        )
        rates_path = os.environ.get("CIMIENTO_RATES")
        rates = load_rates(Path(rates_path)) if rates_path and Path(rates_path).is_file() else None
        print(
            f"intake worker: rates {'loaded' if rates else 'not set'}, polling every {args.poll}s",
            flush=True,
        )
        serve_queue(IntakeClient(core), core, model_from_env(None), rates, args.poll, args.once)
        return 0

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
