"""cimiento-evals retrieval --data data/generated --out evals/reports"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="cimiento-evals")
    sub = parser.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("retrieval", help="hit@k per search method and 'no consta' detection")
    r.add_argument("--data", type=Path, default=Path("data/generated"))
    r.add_argument("--out", type=Path, default=Path("evals/reports"))
    r.add_argument(
        "--config",
        type=Path,
        default=Path("evals/config/search.json"),
        help="where to write the chosen method and thresholds for the service",
    )
    b = sub.add_parser("bid", help="bid analyst: fields, citations, planted instructions")
    b.add_argument("--data", type=Path, default=Path("data/generated"))
    b.add_argument("--out", type=Path, default=Path("evals/reports"))
    b.add_argument("--recordings", type=Path, default=Path("evals/recordings"))
    pl = sub.add_parser("plan", help="planner: valid schedules, duration error, critical path")
    pl.add_argument("--data", type=Path, default=Path("data/generated"))
    pl.add_argument("--out", type=Path, default=Path("evals/reports"))
    pl.add_argument("--recordings", type=Path, default=Path("evals/recordings"))
    args = parser.parse_args(argv)

    if args.cmd == "plan":
        from cimiento_agents.llm import FakeModel, Recordings, model_from_env

        from .plan import evaluate as evaluate_plan

        model = model_from_env(Recordings(args.recordings) if args.recordings.exists() else None)
        label = (
            "keyless baseline (rule-based planner)" if isinstance(model, FakeModel) else "Claude"
        )
        evaluate_plan(args.data, model, args.out, label)
        print((args.out / "plan.md").read_text(encoding="utf-8"))
        return 0

    if args.cmd == "bid":
        from cimiento_agents.llm import Recordings, model_from_env

        from .bid import evaluate as evaluate_bid

        model = model_from_env(Recordings(args.recordings) if args.recordings.exists() else None)
        evaluate_bid(args.data, model, args.out)
        print((args.out / "bid.md").read_text(encoding="utf-8"))
        return 0

    if args.cmd == "retrieval":
        from cimiento_agents.search.models import FastEmbedder, FastReranker

        from .retrieval import evaluate

        result = evaluate(args.data, FastEmbedder(), FastReranker(), args.out)
        args.config.parent.mkdir(parents=True, exist_ok=True)
        config = {"method": result["chosen_method"], **result["thresholds_from_dev"]}
        args.config.write_text(
            json.dumps(config, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        print((args.out / "retrieval.md").read_text(encoding="utf-8"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
