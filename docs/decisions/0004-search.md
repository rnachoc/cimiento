# ADR 0004: Ingestion and search

Status: accepted
Date: 2026-11-18

## Context
Agents must answer with a citation to document and page, and say "no consta" when the answer is not in the documents. The search method had to be chosen by measurement, not intuition.

## Decisions

### Ingestion
PDFs are read with `pypdf` (page footers removed) and split into one chunk per clause (or measurement chapter) and page. Clause headings are detected by pattern (`Cláusula N.`, `Artículo N.`, `N. TITLE`, `N) Title`, `Capítulo N.`). The same chunks come out of the PDF and of the paged text file (tested). After PDF ingestion, every expected quote of the ground truth sits inside one chunk (checked by the eval harness).

### Four methods, ranks at every stage
- **lexical**: BM25 written in about 30 lines (accent folding, short Spanish stop list, light plural stripping). No search server is added.
- **vector**: `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` (Apache-2.0, 384 dimensions) through fastembed (ONNX, CPU). Vectors live in pgvector, in the agents' own database `cimiento_search`, never in the core's database.
- **hybrid**: reciprocal rank fusion (k = 60) of lexical and vector.
- **rerank**: `BAAI/bge-reranker-v2-m3-int8` (Apache-2.0, multilingual) over the top 10 hybrid candidates. The `jina-reranker-v2` multilingual model was rejected for its non-commercial licence.

`POST /search` returns, per result, document, page, heading, text, score and the rank in each stage.

### Measurement protocol
`make eval-retrieval` builds synthetic questions from the ground truth (fields, annex term, measurement rows, unanswerable questions). Dev and eval use different question wording. The method and the "no consta" thresholds are chosen on dev; eval numbers are reported as they come out and never feed back. The evaluation index is in memory; a test checks that pgvector returns the same ranking.

### Models in CI
CI never downloads models: tests use deterministic stand-ins (a hashing embedder and a token-overlap reranker) that are never used to report quality. The real models run only in `make eval-retrieval`, cached in `~/.cache/fastembed`.

## Results
Eval split, chosen on dev: reranked hit@1 84.8 %, hit@5 90.9 % (vector alone reaches 91.4 % hit@5). "No consta" flags 89/90 unanswerable questions but also 206/770 answerable ones. Full report: [evals/reports/retrieval.md](../../evals/reports/retrieval.md); method and thresholds: [evals/config/search.json](../../evals/config/search.json).

## Consequences
- Search is limited to one tender's documents at a time (tens of chunks), which is easier than a large archive; the README says so.
- Questions are synthetic and template-based.
