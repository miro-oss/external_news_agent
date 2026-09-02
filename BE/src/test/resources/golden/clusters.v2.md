# `clusters.v2` measurement

Measured 2026-09-03 for issue #140.

## Dataset

`clusters.v2.json` contains 56 articles manually relabeled by event from real collection run 3862.
Runs 3859, 3862, and 3868 were used to discover and cross-check failure cases.

- Topic 4386: 13 calibration + 12 holdout articles
- Topic 4387: 13 calibration + 18 holdout articles
- Total: 26 calibration + 30 holdout articles
- Articles from one expected event never cross the calibration/holdout boundary.
- Each topic has at least 20 articles, so `common-entity-min-articles=20` activates.
- The fixture keeps the observed title, summary, and only the leading body fragment needed to
  reproduce the failure. It does not copy the full article body.

The earlier note that DGIST issues 419 and 573 were one split issue was incorrect: the issues
belong to topics 4386 and 4387 respectively. The v2 positive case instead uses source articles
2514, 2546, and 2547, which were split inside topic 4386.

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
features without reimplementing the Java extractor. Fixed SimHash content groups are applied to
both the rule candidates and the TF-IDF comparison.

## Results

| Evaluation | Jaccard | Window | Common ratio | Precision | Recall | ARI | V-measure |
|---|---:|---:|---:|---:|---:|---:|---:|
| Configured calibration | 0.50 | 48h | 0.10 | 1.0000 | 0.1707 | 0.2646 | 0.7844 |
| **Configured holdout** | 0.50 | 48h | 0.10 | **0.4000** | **0.0408** | 0.0543 | 0.7833 |
| Selected calibration | 0.40 | 48h | 0.10 | 1.0000 | 0.3171 | 0.4479 | 0.8208 |
| **Selected holdout** | 0.40 | 48h | 0.10 | **0.5000** | **0.0612** | 0.0866 | 0.7900 |
| char_wb TF-IDF calibration | 0.40 | — | — | 1.0000 | 0.4634 | 0.6015 | 0.8609 |
| char_wb TF-IDF holdout | 0.40 | — | — | 0.5714 | 0.0816 | 0.1180 | 0.7968 |

All four common-entity ratio candidates produced the same best calibration result. Changing that
ratio does not improve this dataset.

## Decision

Do not change production thresholds from this measurement. The precision failure happens before
the title/entity rule: source articles 2426, 2430, and 2549 are unrelated, but their stored
`FULLTEXT` bodies contain the same publisher/legal boilerplate and SimHash fixes them into one
content group. No title Jaccard or entity-time threshold can separate a fixed content group.

Issue #141 tracks excluding boilerplate-only bodies from SimHash grouping. Rerun the exact v2
fixture and precommitted grid after that fix. Only return to recall tuning or the embedding decision
table after holdout precision recovers to at least 0.90.

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

The second command intentionally exits with status 1 while the gate fails. Its JSON output contains
`decisionGatePassed: false`.
