# `clusters.v2` measurement

Measured 2026-09-03 for issues #140, #141, and #144.

## Dataset

`clusters.v2.json` contains 56 articles manually relabeled by event from real collection run 3862.
Its `sourceRuns` metadata names only run 3862, which is the run represented by fixture rows. Runs
3859 and 3868 were consulted outside the fixture while discovering and cross-checking cases.

- Topic 4386: 13 calibration + 12 holdout articles
- Topic 4387: 13 calibration + 18 holdout articles
- Total: 26 calibration + 30 holdout articles
- Articles from one expected event never cross the calibration/holdout boundary.
- Each topic has at least 20 articles only when both splits are combined. Java therefore computes
  the common-entity DF cutoff over the complete corpus before Python evaluates either split; the
  calibration and holdout DF statistics are not independent.
- The fixture keeps the observed title, summary, and at most 120 leading body characters. All 48
  `FULLTEXT` rows are truncated, so SimHash groups reproduce the stored fragments rather than prove
  the fingerprints of the original full bodies.

The earlier note that DGIST issues 419 and 573 were one split issue was incorrect: the issues
belong to topics 4386 and 4387 respectively. The v2 positive case instead uses source articles
2514, 2546, and 2547, which were split inside topic 4386.

Source article 2410 was also relabeled during the full-body replay. Its K-NPU public-procurement
story is unrelated to the Applied Materials 3D-scaling announcement and now has the independent
label `k-npu-government-contracts`.

## Precommitted grid and gate

The candidates and gates were fixed before reading the metrics.

| Axis | Candidates |
|---|---|
| Title Jaccard | 0.40 to 0.75 in 0.05 increments |
| Entity time window | 24h / 48h / 72h |
| Common entity document ratio | 0.05 / 0.10 / 0.15 / 0.20 |
| Selection | Highest recall among calibration precision >= 0.90 |
| Final gate | Holdout precision >= 0.90 and recall >= 0.85 |

Java calculates entity overlap separately for every common-entity ratio. Python consumes those
features without reimplementing the Java extractor. Fixed SimHash content groups preserve the
Java representative even when a group crosses topic boundaries. The report emits two TF-IDF
comparisons: one with the same SimHash preprocessing as the rule candidates and one standalone.

## Results

| Evaluation | Jaccard | Window | Common ratio | Precision | Recall | ARI | V-measure |
|---|---:|---:|---:|---:|---:|---:|---:|
| Configured calibration | 0.50 | 48h | 0.10 | 1.0000 | 0.1707 | 0.2646 | 0.7844 |
| **Configured holdout** | 0.50 | 48h | 0.10 | **0.4000** | **0.0408** | 0.0543 | 0.7833 |
| Selected calibration | 0.40 | 48h | 0.10 | 1.0000 | 0.3171 | 0.4479 | 0.8208 |
| **Selected holdout** | 0.40 | 48h | 0.10 | **0.5000** | **0.0612** | 0.0866 | 0.7900 |
| char_wb TF-IDF + SimHash calibration | 0.40 | — | — | 1.0000 | 0.4634 | 0.6015 | 0.8609 |
| char_wb TF-IDF + SimHash holdout | 0.40 | — | — | 0.5714 | 0.0816 | 0.1180 | 0.7968 |
| standalone char_wb TF-IDF calibration | 0.40 | — | — | 1.0000 | 0.4634 | 0.6015 | 0.8609 |
| standalone char_wb TF-IDF holdout | 0.40 | — | — | 1.0000 | 0.0816 | 0.1363 | 0.8210 |

All four common-entity ratio candidates produced the same best calibration result. Changing that
ratio does not improve this dataset.

## Decision

Do not change production thresholds from this measurement. In the truncated fixture, source
articles 2426, 2430, and 2549 are unrelated but their 120-character `FULLTEXT` fragments contain
the same publisher/legal boilerplate. SimHash consequently fixes them into one content group
before the title/entity rule. This is a fixture-fragment observation, not proof that the original
stored bodies have the same SimHash; confirm the source rows before attributing the production
failure to body extraction or SimHash tuning.

Issue #141 tracks hardening SimHash grouping against boilerplate-only bodies and adding a lossless
regression case. Rerun the exact v2 fixture and precommitted grid after that fix, but keep its
truncation caveat. Only return to recall tuning or the embedding decision table after holdout
precision recovers to at least 0.90 on a source-faithful replay.

## After issue #141

The shared article-body cleaner now recognizes a dense publisher/legal footer at the end of the
body, including NFKC-normalized copyright symbols, without cutting a legal phrase quoted earlier
in the article. The configured `min-article-content-length` (default 200) must remain after that
cleanup before SimHash is calculated. A separate lossless regression test uses bodies longer than
the cutoff to prove that boilerplate-only bodies are excluded while syndicated article text still
groups. Investigation actions now re-run analysis for refreshed articles before measuring
supported evidence; these investigation fixes do not affect the clustering metrics below.

All 48 `FULLTEXT` rows in this fixture contain only 120-character fragments, so the conditional
replay intentionally creates no fixed SimHash groups. It therefore verifies that truncated
boilerplate cannot force an over-merge, but it cannot measure full-body duplicate recall.

| Evaluation after #141 | Jaccard | Window | Common ratio | Precision | Recall | ARI | V-measure |
|---|---:|---:|---:|---:|---:|---:|---:|
| Configured calibration | 0.50 | 48h | 0.10 | 1.0000 | 0.1707 | 0.2646 | 0.7844 |
| **Configured holdout** | 0.50 | 48h | 0.10 | **1.0000** | **0.0408** | 0.0702 | 0.8073 |
| Selected calibration | 0.40 | 48h | 0.10 | 1.0000 | 0.3171 | 0.4479 | 0.8208 |
| **Selected holdout** | 0.40 | 48h | 0.10 | **1.0000** | **0.0816** | 0.1363 | 0.8210 |

The fixture precision gate now passes, while the recall gate still fails and
`decisionGatePassed` remains false. Treat this as a regression result for the defensive cutoff,
not as a source-faithful final threshold decision; full-body fingerprints or a fresh real
collection are still required before changing production thresholds.

## Full-body replay and issue #144

The local run 3862 rows were still available, so the 56 fixture IDs were replayed with their full
stored bodies. Ten articles had been refreshed after run 3862; for those, the earliest later
`news_article_versions` row restores the title and body that existed immediately before that
refresh. Version rows do not retain summaries, so the replay omits the summary for those ten
articles instead of mixing states. The committed `clusters.v2-export.sql` records this extraction
without committing any article body.

The source-faithful replay contained 48 usable full bodies. SimHash formed one two-article group,
and both articles were correctly labeled `sk-japan-factory`; there were no mixed content groups.
After the #141 fix, the remaining failure was under-merging: almost every sampled missed positive
pair still shared an explicit organization in its title or summary (DGIST, Applied Materials,
SK hynix, or LG Electronics). This rejects the local-NLI/embedding condition that the misses must
mostly lack usable title/entity overlap.

Issue #144 therefore preserves the global title Jaccard threshold and adds one conservative edge:

- a known organization must appear in both title/summary fields;
- title Jaccard must be at least 0.125; and
- publication times must be no more than 24 hours apart.

Product codes and body-only background mentions cannot satisfy this edge. The organization names
come from a closed alias map, and short ASCII aliases require word boundaries. When sweep candidates
tie exactly, the evaluator retains the configured title threshold rather than reporting a
meaningless stricter candidate.

| Final evaluation | Jaccard | Entity window | Org title/window | Precision | Recall | ARI | V-measure |
|---|---:|---:|---:|---:|---:|---:|---:|
| v1 calibration | 0.50 | 48h | 0.125 / 24h | 1.0000 | 0.9750 | 0.9871 | 0.9962 |
| **v1 holdout** | 0.50 | 48h | 0.125 / 24h | **1.0000** | **1.0000** | 1.0000 | 1.0000 |
| v2 fixture calibration | 0.50 | 48h | 0.125 / 24h | 1.0000 | 0.9024 | 0.9417 | 0.9751 |
| **v2 fixture holdout** | 0.50 | 48h | 0.125 / 24h | **1.0000** | **1.0000** | 1.0000 | 1.0000 |
| v2 full-body calibration | 0.50 | 48h | 0.125 / 24h | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| **v2 full-body holdout** | 0.50 | 48h | 0.125 / 24h | **1.0000** | **1.0000** | 1.0000 | 1.0000 |

Both v2 paths pass the precommitted holdout precision/recall gate. The configured global values
remain 0.50, 48h, and common-entity ratio 0.10. On this decision table, clusters.v2 is finalized
without an embedding or local-NLI stage.

## Reproduction

```bash
cd BE
./gradlew test --tests '*IssueClustererGoldenExportTest' \
  --tests '*IssueClustererRealGoldenExportTest'

cd ../agent
uv run --group dev python -m app.eval.cluster_sweep \
  --golden ../BE/src/test/resources/golden/clusters.v2.json \
  --java-pairs ../BE/build/reports/clusters/pairs.v2.json
```

Before issue #144, the second command intentionally exited with status 1 because the gate failed.
It now exits with status 0 and emits `decisionGatePassed: true`.

For a source-faithful replay, run `clusters.v2-export.sql` through SQL*Plus with
`NLS_LANG=AMERICAN_AMERICA.AL32UTF8`, save the JSONL outside the repository, then run:

```bash
cd BE
CLUSTERS_V2_REPLAY_ARTICLES=/private/tmp/clusters-v2-run3862-fullbody.jsonl \
CLUSTERS_V2_REPLAY_OUTPUT=build/reports/clusters/pairs.v2.fullbody.json \
./gradlew test --tests '*IssueClustererRealGoldenExportTest'

cd ../agent
uv run --group dev python -m app.eval.cluster_sweep \
  --golden ../BE/src/test/resources/golden/clusters.v2.json \
  --java-pairs ../BE/build/reports/clusters/pairs.v2.fullbody.json
```

After issue #144, both the ordinary fixture command and this full-body command exit with status 0
and emit `decisionGatePassed: true`.
