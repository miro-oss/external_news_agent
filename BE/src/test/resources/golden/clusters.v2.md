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
articles instead of mixing states. `clusters.v2-export.sql` uses an ordered `row_number()` to make
that choice deterministic and does not commit any article body.

The replay contained 48 usable full bodies. After the #141 fix, most sampled missed positive pairs
shared an explicit organization in title or summary. Issue #144 therefore added an auxiliary
organization edge while preserving the configured global title threshold:

- a known organization must appear in both title/summary fields;
- title Jaccard must be at least 0.125; and
- publication times must be no more than 24 hours apart.

Review exposed two unsafe bypasses in the first implementation. An organization appearing in at
least eight voting documents is now removed from auxiliary overlap, so one large company cannot
join a topic. Organizations found only in article bodies are not added to the generic two-entity
edge; this prevents background mentions such as Amazon and Meta from joining unrelated stories.
Korean aliases use letter boundaries with an explicit postposition allowance, so words such as
`인텔리전스`, `애플리케이션`, and `메타버스` do not match company aliases. The ambiguous name
`미래산업` requires a corporate marker.

The organization threshold and window were introduced after the original grid was precommitted.
The review therefore adds a disclosed post-hoc sensitivity grid rather than calling them
precommitted candidates:

| Added axis | Candidates |
|---|---|
| Organization title Jaccard | 0.10 / 0.125 / 0.15 / 0.20 |
| Organization time window | 12h / 24h / 48h |

The evaluator now mirrors Java's six-hour breaking-news branch. It reports all configurations tied
on selection metrics, then uses the stricter title/organization thresholds and shorter organization
window only as a deterministic tie-break. There are 192 metric-tied candidates on v2, so the chosen
configuration is not uniquely identified by calibration data.

| Final evaluation | Jaccard | Entity window | Org title/window | Precision | Recall | ARI | V-measure |
|---|---:|---:|---:|---:|---:|---:|---:|
| v1 selected calibration | 0.50 | 48h | 0.20 / 12h | 1.0000 | 0.9750 | 0.9871 | 0.9962 |
| **v1 selected holdout** | 0.50 | 48h | 0.20 / 12h | **1.0000** | **1.0000** | 1.0000 | 1.0000 |
| v2 fixture configured calibration | 0.50 | 48h | 0.125 / 24h | 1.0000 | 0.5854 | 0.7116 | 0.8844 |
| **v2 fixture configured holdout** | 0.50 | 48h | 0.125 / 24h | **1.0000** | **0.7045** | 0.8109 | 0.9596 |
| v2 fixture selected calibration | 0.45 | 48h | 0.20 / 24h | 1.0000 | 0.6585 | 0.7712 | 0.9027 |
| **v2 fixture selected holdout** | 0.45 | 48h | 0.20 / 24h | **1.0000** | **0.5000** | 0.6426 | 0.9265 |
| v2 full-body configured calibration | 0.50 | 48h | 0.125 / 24h | 1.0000 | 0.7317 | 0.8266 | 0.9310 |
| **v2 full-body configured holdout** | 0.50 | 48h | 0.125 / 24h | **1.0000** | **1.0000** | 1.0000 | 1.0000 |
| v2 full-body selected calibration | 0.45 | 48h | 0.20 / 24h | 1.0000 | 0.8049 | 0.8782 | 0.9514 |
| **v2 full-body selected holdout** | 0.45 | 48h | 0.20 / 24h | **1.0000** | **0.5000** | 0.6426 | 0.9265 |

Source article 2410 was relabeled only after inspecting replay predictions, so this holdout is no
longer independent. Excluding 2410 leaves pairwise precision/recall unchanged: selected full-body
holdout remains 1.0000/0.5000 and configured full-body holdout remains 1.0000/1.0000. The evaluator
emits these values under `postHocRelabelSensitivity`.

The configured full-body regression is useful evidence for the targeted fix, but it is not an
unbiased final threshold result. The independently selected configuration fails the recall gate.
Consequently `clusters.v2` remains a regression set, production thresholds are not changed from
0.50/48h/0.10 plus the reviewed 0.125/24h organization edge, and the embedding/local-NLI decision
is deferred until a fresh run is labeled without inspecting predictions.

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

The v2 command exits with status 1 because the independently selected configuration does not pass
the holdout recall gate. This is an expected measurement result, not a test failure.

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

Both the ordinary fixture command and the full-body command currently exit with status 1 and emit
`decisionGatePassed: false`. The configured full-body metrics and the post-hoc relabel sensitivity
remain available in the JSON report for regression tracking.
