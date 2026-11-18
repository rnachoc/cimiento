"""Retrieval eval: hit@1 and hit@5 per method and question type, and "no consta" detection.

Protocol:
1. Index the dev split (PDF ingestion), run every method on dev questions.
2. Choose the method by dev hit@5 (then hit@1), and set each method's "no consta"
   threshold on dev (best balanced accuracy between answerable and unanswerable).
3. Index the eval split and report every method with the dev thresholds. Eval numbers
   never feed back into any choice.
"""

from __future__ import annotations

import json
import statistics
import sys
import time
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path

from cimiento_agents.ingest.chunking import Chunk, chunk_tender
from cimiento_agents.search.models import Embedder, Reranker
from cimiento_agents.search.pipeline import METHODS, SearchPipeline
from cimiento_agents.search.store import InMemoryStore
from cimiento_agents.textnorm import normalize

from .questions import Question, questions_for_split

K = 5


@dataclass
class Outcome:
    tender: str
    kind: str
    field: str
    question: str
    rank: dict[str, int | None]  # first rank of a correct chunk per method (None if not in top 50)
    top_score: dict[str, float | None]


def is_hit(chunk: Chunk, q: Question) -> bool:
    return (
        chunk.document == q.document
        and chunk.page == q.page
        and normalize(q.quote or "") in normalize(chunk.text)
    )


def build_pipeline(
    split_dir: Path, embedder: Embedder, reranker: Reranker | None
) -> SearchPipeline:
    store = InMemoryStore()
    for folder in sorted((split_dir / "tenders").iterdir()):
        if folder.name.endswith("-gemelo"):
            continue
        chunks = chunk_tender(folder, "pdf")
        store.add(chunks, embedder.embed([c.text for c in chunks]))
    return SearchPipeline(store, embedder, reranker)


def run_split(
    split_dir: Path, split: str, pipeline: SearchPipeline, methods: tuple[str, ...] = METHODS
) -> tuple[list[Outcome], dict[str, float]]:
    questions = questions_for_split(split_dir, split)
    outcomes: list[Outcome] = []
    coverage_total = coverage_found = 0
    for n, q in enumerate(questions, start=1):
        if n % 25 == 0 or n == len(questions):
            print(f"{split}: {n}/{len(questions)} questions", file=sys.stderr, flush=True)
        if q.kind != "sin_respuesta":
            coverage_total += 1
            coverage_found += any(is_hit(c, q) for c in pipeline.store.chunks(q.tender))
        ranks: dict[str, int | None] = {}
        tops: dict[str, float | None] = {}
        for m in methods:
            res = pipeline.search(q.tender, q.text, k=50, method=m)
            tops[m] = res.top_score
            ranks[m] = None
            if q.kind != "sin_respuesta":
                ranks[m] = next((i + 1 for i, h in enumerate(res.hits) if is_hit(h.chunk, q)), None)
        outcomes.append(Outcome(q.tender, q.kind, q.field, q.text, ranks, tops))
    chunks = [len(pipeline.store.chunks(t)) for t in pipeline.store.tenders()]
    stats = {
        "tenders": len(chunks),
        "chunks_mean": round(statistics.mean(chunks), 1),
        "chunks_min": min(chunks),
        "chunks_max": max(chunks),
        "answerable_with_quote_in_a_chunk": f"{coverage_found}/{coverage_total}",
    }
    return outcomes, stats


def hit_table(outcomes: list[Outcome], methods: tuple[str, ...]) -> dict[str, dict[str, dict]]:
    """method -> kind (and 'all') -> {n, hit@1, hit@5}."""
    table: dict[str, dict[str, dict]] = {}
    for m in methods:
        groups: dict[str, list[Outcome]] = defaultdict(list)
        for o in outcomes:
            if o.kind != "sin_respuesta":
                groups[o.kind].append(o)
                groups["all"].append(o)
        table[m] = {
            kind: {
                "n": len(items),
                "hit@1": sum(1 for o in items if o.rank[m] == 1),
                f"hit@{K}": sum(1 for o in items if o.rank[m] is not None and o.rank[m] <= K),
            }
            for kind, items in sorted(groups.items())
        }
    return table


def tune_threshold(outcomes: list[Outcome], method: str) -> float:
    """Threshold on the top score that best separates answerable from unanswerable (dev)."""
    pos = [o.top_score[method] for o in outcomes if o.kind != "sin_respuesta"]
    neg = [o.top_score[method] for o in outcomes if o.kind == "sin_respuesta"]
    candidates = sorted({s for s in pos + neg if s is not None})
    best, best_t = -1.0, candidates[0] if candidates else 0.0
    for t in candidates:
        tpr = sum(1 for s in pos if s is not None and s >= t) / len(pos)
        tnr = sum(1 for s in neg if s is None or s < t) / len(neg)
        score = (tpr + tnr) / 2
        if score > best:
            best, best_t = score, t
    return best_t


def no_consta_rates(outcomes: list[Outcome], method: str, threshold: float) -> dict[str, object]:
    pos = [o for o in outcomes if o.kind != "sin_respuesta"]
    neg = [o for o in outcomes if o.kind == "sin_respuesta"]
    flagged_neg = sum(
        1 for o in neg if o.top_score[method] is None or o.top_score[method] < threshold
    )
    flagged_pos = sum(
        1 for o in pos if o.top_score[method] is None or o.top_score[method] < threshold
    )
    return {
        "threshold": threshold,
        "unanswerable_flagged": f"{flagged_neg}/{len(neg)}",
        "answerable_wrongly_flagged": f"{flagged_pos}/{len(pos)}",
    }


def choose_method(table: dict[str, dict[str, dict]]) -> str:
    return max(
        table,
        key=lambda m: (table[m]["all"][f"hit@{K}"], table[m]["all"]["hit@1"], -METHODS.index(m)),
    )


def pct(n: int, d: int) -> str:
    return f"{100 * n / d:.1f} % ({n}/{d})" if d else "n/a"


def render_report(result: dict) -> str:
    lines = [
        "# Retrieval eval",
        "",
        f"Generated {result['date']} by `make eval-retrieval`. Embeddings `{result['embedder']}`, "
        f"reranker `{result['reranker']}` (local models, no LLM involved).",
        "",
        "**Caveat: questions are synthetic**, generated from templates and the ground truth. "
        "Each search is limited to one tender's documents "
        f"({result['eval']['stats']['chunks_mean']} chunks per tender on average, "
        f"{result['eval']['stats']['chunks_min']} to {result['eval']['stats']['chunks_max']}). "
        "Real questions over real documents will be harder.",
        "",
        f"Method chosen on the dev split: **{result['chosen_method']}** (best dev hit@{K}, then hit@1). "
        'Thresholds for "no consta" were also set on dev. The tables below are the eval split, '
        "with question wording that was not used on dev.",
        "",
        f"## Eval split: hit@1 and hit@{K} by method and question type",
        "",
        f"| Method | Type | n | hit@1 | hit@{K} |",
        "|---|---|---|---|---|",
    ]
    for m, kinds in result["eval"]["table"].items():
        for kind, v in kinds.items():
            lines.append(
                f"| {m} | {kind} | {v['n']} | {pct(v['hit@1'], v['n'])} | "
                f"{pct(v[f'hit@{K}'], v['n'])} |"
            )
    lines += [
        "",
        '## Eval split: "no consta" (answer not in the documents)',
        "",
        "| Method | Threshold (from dev) | Unanswerable correctly flagged | Answerable wrongly flagged |",
        "|---|---|---|---|",
    ]
    for m, r in result["eval"]["no_consta"].items():
        lines.append(
            f"| {m} | {r['threshold']:.4f} | {r['unanswerable_flagged']} | "
            f"{r['answerable_wrongly_flagged']} |"
        )
    lines += [
        "",
        "## Dev split (used for choices)",
        "",
        f"| Method | n | hit@1 | hit@{K} |",
        "|---|---|---|---|",
    ]
    for m, kinds in result["dev"]["table"].items():
        v = kinds["all"]
        lines.append(
            f"| {m} | {v['n']} | {pct(v['hit@1'], v['n'])} | {pct(v[f'hit@{K}'], v['n'])} |"
        )
    lines += [
        "",
        "## Index",
        "",
        f"- Eval: {result['eval']['stats']}",
        f"- Dev: {result['dev']['stats']}",
        "",
    ]
    return "\n".join(lines)


def evaluate(data_dir: Path, embedder: Embedder, reranker: Reranker | None, out_dir: Path) -> dict:
    methods = METHODS if reranker is not None else METHODS[:3]
    t0 = time.time()
    dev_pipe = build_pipeline(data_dir / "dev", embedder, reranker)
    dev, dev_stats = run_split(data_dir / "dev", "dev", dev_pipe, methods)
    dev_table = hit_table(dev, methods)
    thresholds = {m: tune_threshold(dev, m) for m in methods}
    chosen = choose_method(dev_table)

    eval_pipe = build_pipeline(data_dir / "eval", embedder, reranker)
    ev, eval_stats = run_split(data_dir / "eval", "eval", eval_pipe, methods)
    result = {
        "date": time.strftime("%Y-%m-%d"),
        "embedder": embedder.name,
        "reranker": getattr(reranker, "name", None),
        "k": K,
        "chosen_method": chosen,
        "thresholds_from_dev": thresholds,
        "dev": {
            "table": dev_table,
            "stats": dev_stats,
            "no_consta": {m: no_consta_rates(dev, m, thresholds[m]) for m in methods},
        },
        "eval": {
            "table": hit_table(ev, methods),
            "stats": eval_stats,
            "no_consta": {m: no_consta_rates(ev, m, thresholds[m]) for m in methods},
        },
        "seconds": round(time.time() - t0, 1),
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "retrieval.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    (out_dir / "retrieval.md").write_text(render_report(result), encoding="utf-8")
    (out_dir / "retrieval_outcomes_eval.jsonl").write_text(
        "".join(json.dumps(asdict(o), ensure_ascii=False) + "\n" for o in ev), encoding="utf-8"
    )
    return result
