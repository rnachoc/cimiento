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
    st = sub.add_parser("site", help="site progress: planted delays, alarms, reports")
    st.add_argument("--data", type=Path, default=Path("data/generated"))
    st.add_argument("--out", type=Path, default=Path("evals/reports"))
    st.add_argument("--recordings", type=Path, default=Path("evals/recordings"))
    cr = sub.add_parser("cost", help="tokens and cost per model from exported spans; content check")
    cr.add_argument("--spans", type=Path, required=True)
    cr.add_argument("--prices", type=Path, default=Path("evals/config/prices.json"))
    cr.add_argument("--data", type=Path, default=Path("data/generated"))
    cr.add_argument("--out", type=Path, default=Path("evals/reports"))
    sc = sub.add_parser("scorecard", help="compare eval reports with the committed baseline")
    sc.add_argument("--reports", type=Path, default=Path("evals/reports"))
    sc.add_argument("--baseline", type=Path, default=Path("evals/baseline.json"))
    sc.add_argument(
        "--write-baseline", action="store_true", help="store the current values as baseline"
    )
    sc.add_argument("--label", default="keyless baseline (rule-based reader)")
    args = parser.parse_args(argv)

    if args.cmd == "scorecard":
        from .scorecard import collect, compare, render

        current = collect(args.reports)
        if args.write_baseline:
            payload = {"label": args.label, "tolerance": 0.0, **current}
            text = json.dumps(payload, indent=2, sort_keys=True)
            args.baseline.write_text(text + "\n", encoding="utf-8")
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
        ok, lines = compare(current, baseline)
        bid = json.loads((args.reports / "bid.json").read_text(encoding="utf-8"))["eval"]
        source = ", ".join(bid["sources"])
        (args.reports / "scorecard.md").write_text(
            render(ok, lines, baseline, source), encoding="utf-8"
        )
        print((args.reports / "scorecard.md").read_text(encoding="utf-8"))
        return 0 if ok else 1

    if args.cmd == "cost":
        from .cost import check_no_content, load_spans, render, report

        spans = load_spans(args.spans)
        content = check_no_content(spans, args.data)
        cost = report(spans, json.loads(args.prices.read_text(encoding="utf-8")))
        args.out.mkdir(parents=True, exist_ok=True)
        (args.out / "cost.md").write_text(render(cost, content), encoding="utf-8")
        summary = json.dumps({"cost": cost, "content": content}, indent=2)
        (args.out / "cost.json").write_text(summary + "\n", encoding="utf-8")
        print((args.out / "cost.md").read_text(encoding="utf-8"))
        return 1 if content["violations"] else 0

    if args.cmd == "site":
        from cimiento_agents.llm import FakeModel, Recordings, model_from_env

        from .site import evaluate as evaluate_site

        model = model_from_env(Recordings(args.recordings) if args.recordings.exists() else None)
        label = "keyless baseline (rule-based)" if isinstance(model, FakeModel) else "Claude"
        evaluate_site(args.data, model, args.out, label)
        print((args.out / "site.md").read_text(encoding="utf-8"))
        return 0

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
