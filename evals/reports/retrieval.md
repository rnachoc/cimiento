# Retrieval eval

Generated 2026-11-18 by `make eval-retrieval`. Embeddings `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`, reranker `BAAI/bge-reranker-v2-m3-int8` (local models, no LLM involved).

**Caveat: questions are synthetic**, generated from templates and the ground truth. Each search is limited to one tender's documents (33.1 chunks per tender on average, 29 to 37). Real questions over real documents will be harder.

Method chosen on the dev split: **rerank** (best dev hit@5, then hit@1). Thresholds for "no consta" were also set on dev. The tables below are the eval split, with question wording that was not used on dev.

## Eval split: hit@1 and hit@5 by method and question type

| Method | Type | n | hit@1 | hit@5 |
|---|---|---|---|---|
| lexical | all | 770 | 67.9 % (523/770) | 75.3 % (580/770) |
| lexical | anexo | 30 | 0.0 % (0/30) | 26.7 % (8/30) |
| lexical | campo | 360 | 39.7 % (143/360) | 53.3 % (192/360) |
| lexical | medicion | 380 | 100.0 % (380/380) | 100.0 % (380/380) |
| vector | all | 770 | 64.5 % (497/770) | 91.4 % (704/770) |
| vector | anexo | 30 | 43.3 % (13/30) | 100.0 % (30/30) |
| vector | campo | 360 | 41.7 % (150/360) | 82.8 % (298/360) |
| vector | medicion | 380 | 87.9 % (334/380) | 98.9 % (376/380) |
| hybrid | all | 770 | 70.4 % (542/770) | 83.4 % (642/770) |
| hybrid | anexo | 30 | 30.0 % (9/30) | 100.0 % (30/30) |
| hybrid | campo | 360 | 45.8 % (165/360) | 64.4 % (232/360) |
| hybrid | medicion | 380 | 96.8 % (368/380) | 100.0 % (380/380) |
| rerank | all | 770 | 84.8 % (653/770) | 90.9 % (700/770) |
| rerank | anexo | 30 | 76.7 % (23/30) | 100.0 % (30/30) |
| rerank | campo | 360 | 69.4 % (250/360) | 80.6 % (290/360) |
| rerank | medicion | 380 | 100.0 % (380/380) | 100.0 % (380/380) |

## Eval split: "no consta" (answer not in the documents)

| Method | Threshold (from dev) | Unanswerable correctly flagged | Answerable wrongly flagged |
|---|---|---|---|
| lexical | 7.6765 | 90/90 | 334/770 |
| vector | 0.5833 | 66/90 | 386/770 |
| hybrid | 0.0328 | 45/90 | 275/770 |
| rerank | -1.4212 | 89/90 | 206/770 |

## Dev split (used for choices)

| Method | n | hit@1 | hit@5 |
|---|---|---|---|
| lexical | 830 | 92.3 % (766/830) | 97.5 % (809/830) |
| vector | 830 | 79.4 % (659/830) | 96.0 % (797/830) |
| hybrid | 830 | 87.3 % (725/830) | 97.3 % (808/830) |
| rerank | 830 | 99.0 % (822/830) | 99.8 % (828/830) |

## Index

- Eval: {'tenders': 30, 'chunks_mean': 33.1, 'chunks_min': 29, 'chunks_max': 37, 'answerable_with_quote_in_a_chunk': '770/770'}
- Dev: {'tenders': 30, 'chunks_mean': 33.2, 'chunks_min': 29, 'chunks_max': 37, 'answerable_with_quote_in_a_chunk': '830/830'}
