# `clusters.v2` measurement

Measured 2026-09-03 for issue #140.

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
